package com.hermexapp.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.hermexapp.android.auth.AuthManager
import com.hermexapp.android.auth.KeystoreSecretStore
import com.hermexapp.android.auth.SecretStore
import com.hermexapp.android.config.AppPrefs
import com.hermexapp.android.config.KeyValueStore
import com.hermexapp.android.persistence.SentPromptsStore
import com.hermexapp.android.features.sessionlist.SessionRepository
import com.hermexapp.android.features.sessionlist.SessionRepositoryImpl
import com.hermexapp.android.network.ApiClient
import com.hermexapp.android.network.SessionCookieJar
import com.hermexapp.android.network.SseClient
import com.hermexapp.android.persistence.CacheStore
import com.hermexapp.android.persistence.NoteStore
import com.hermexapp.android.persistence.PromptStore
import com.hermexapp.android.persistence.HermexDatabase
import com.hermexapp.android.persistence.InMemoryCacheStore
import com.hermexapp.android.persistence.InMemoryNoteStore
import com.hermexapp.android.persistence.InMemoryPromptStore
import com.hermexapp.android.persistence.RoomCacheStore
import com.hermexapp.android.persistence.RoomNoteStore
import com.hermexapp.android.persistence.RoomPromptStore
import com.hermexapp.android.platform.AppVisibility
import com.hermexapp.android.platform.RunNotifications
import com.hermexapp.android.platform.SharedDraftStore
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * Manual dependency wiring — no DI framework (the dependency list is locked).
 * One process-wide OkHttpClient (with the persistent session cookie jar) and
 * one AuthManager, mirroring the iOS app's shared cookie storage + AuthManager.
 */
class HermexApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(KeystoreSecretStore(this), this)
        // Bound the on-disk transcript cache once per launch (age + count cap).
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            runCatching { container.cacheStore.prune() }
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                AppVisibility.foregroundActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                AppVisibility.foregroundActivities--
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

class AppContainer(secretStore: SecretStore, context: Context? = null) {

    val cookieJar = SessionCookieJar(secretStore)

    /** Multi-server registry + per-server custom headers (null in the test path). */
    val serverRegistry: com.hermexapp.android.config.ServerRegistry? =
        context?.let { com.hermexapp.android.config.ServerRegistry(it) }

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        // Hermex 0.6 / JKP pairing freeze: Bearer grant on all API + SSE calls.
        .addInterceptor(
            com.hermexapp.android.network.BearerAuthInterceptor { host ->
                secretStore.load(SecretStore.Key.PAIR_GRANT, host)
            },
        )
        .apply {
            serverRegistry?.let { registry ->
                addInterceptor(com.hermexapp.android.network.CustomHeaderInterceptor(registry::headersForHost))
            }
        }
        .build()

    val cacheStore: CacheStore
    val noteStore: NoteStore
    val promptStore: PromptStore

    init {
        if (context != null) {
            val db = HermexDatabase.build(context)
            cacheStore = RoomCacheStore(db.cachedPayloadDao())
            noteStore = RoomNoteStore(db.notesDao())
            promptStore = RoomPromptStore(db.promptsDao())
        } else {
            cacheStore = InMemoryCacheStore()
            noteStore = InMemoryNoteStore()
            promptStore = InMemoryPromptStore()
        }
    }

    val prefs: AppPrefs? = context?.let { AppPrefs(it) }

    /**
     * Rolling history of prompts the operator has sent, so the prompts screen
     * can offer them for saving after the fact. Device-local, like the drafts
     * and the prompt library beside it.
     */
    val sentPrompts: SentPromptsStore? = context?.let {
        SentPromptsStore(KeyValueStore.forPrefs(it, "hermex_sent_prompts"))
    }

    val notifications: RunNotifications? = context?.let { RunNotifications(it) }

    val sharedDraftStore = SharedDraftStore()

    val authManager = AuthManager(
        secretStore = secretStore,
        cookieJar = cookieJar,
        clientFactory = { baseUrl -> ApiClient(baseUrl, httpClient) },
        registry = serverRegistry,
    )

    /**
     * The server URL the app is currently configured to talk to, or `null`
     * when no pairing/onboarding has been completed yet. Read from
     * [com.hermexapp.android.auth.AuthManager]'s reactive state so it
     * stays correct after the user changes servers via Settings or
     * pairing intent without needing a recompose-time null-check dance.
     *
     * Surfaced on the SessionList error banner so a "JKP is unreachable"
     * always comes with the URL that failed — solves the
     * `127.0.0.1:8787` on a real device classic without forcing the user
     * to dig into Settings.
     */
    fun currentBaseUrl(): HttpUrl? = authManager.state.value.server

    fun apiClient(baseUrl: HttpUrl): ApiClient = ApiClient(baseUrl, httpClient)

    fun sessionRepository(baseUrl: HttpUrl): SessionRepository =
        SessionRepositoryImpl(apiClient(baseUrl), cacheStore)

    fun sseClient(): SseClient = SseClient(httpClient)
}
