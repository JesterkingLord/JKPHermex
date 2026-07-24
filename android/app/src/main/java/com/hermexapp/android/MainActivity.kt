package com.hermexapp.android

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hermexapp.android.auth.AuthManager
import com.hermexapp.android.config.AccentPreset
import com.hermexapp.android.config.ThemeChoice
import com.hermexapp.android.features.chat.ChatDisplayPrefs
import com.hermexapp.android.features.chat.ChatScreen
import com.hermexapp.android.features.composer.InsertPaletteViewModel
import com.hermexapp.android.features.chat.ChatViewModel
import com.hermexapp.android.features.chat.LocalChatDisplayPrefs
import com.hermexapp.android.features.onboarding.OnboardingScreen
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import com.hermexapp.android.features.onboarding.OnboardingViewModel
import com.hermexapp.android.features.panels.PanelKind
import com.hermexapp.android.features.panels.PanelScreen
import com.hermexapp.android.features.panels.PanelsViewModel
import com.hermexapp.android.features.sessionlist.SessionListScreen
import com.hermexapp.android.features.sessionlist.SessionListViewModel
import com.hermexapp.android.features.settings.SettingsScreen
import com.hermexapp.android.features.workspace.FileBrowserScreen
import com.hermexapp.android.features.workspace.GitScreen
import com.hermexapp.android.features.workspace.WorkspaceViewModel
import com.hermexapp.android.platform.RunNotifications
import com.hermexapp.android.ui.MainScreenTab
import com.hermexapp.android.ui.PhoneDrawerScaffold
import com.hermexapp.android.ui.theme.HermexTheme
import com.hermexapp.android.ui.theme.accentColorFromHex
import kotlinx.coroutines.launch
import okhttp3.HttpUrl

class MainActivity : ComponentActivity() {

    private val onboardingViewModel: OnboardingViewModel by viewModels {
        val container = (application as HermexApp).container
        viewModelFactory {
            initializer {
                OnboardingViewModel(
                    authGateway = container.authManager,
                    savedServerUrl = container.authManager.state.value.server?.toString(),
                )
            }
        }
    }

    private val notificationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // EXCELLENCE 0.6.1 — chat scaffold: force both system bars transparent to
        // kill the gray scrim that `enableEdgeToEdge()`'s default
        // navigationBarStyle draws under the keyboard when the IME is up.
        //
        // Without this, the area between the composer (which the Scaffold has
        // pushed up by `.imePadding()`) and the keyboard's top edge is filled
        // by the system window-background scrim as a visible dark band. The
        // chat Scaffold's `containerColor = palette.canvas` doesn't reach that
        // area because the keyboard's own window covers it — so the scrim wins.
        //
        // Setting BOTH bars to fully transparent (no light/dark scrim) tells
        // the platform to leave the area the chat canvas, which makes the gap
        // disappear. Status bar uses TRANSPARENT for both light and dark
        // variants so the chat's dark canvas shows through the status bar.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            ),
        )
        val container = (application as HermexApp).container
        handleIncomingIntent(intent)
        requestNotificationPermissionIfNeeded()

        setContent {
            val themeChoice by (container.prefs?.theme
                ?: kotlinx.coroutines.flow.MutableStateFlow(ThemeChoice.SYSTEM)).collectAsState()
            val accent by (container.prefs?.accent
                ?: kotlinx.coroutines.flow.MutableStateFlow(AccentPreset.GOLD)).collectAsState()
            val expandThinking by (container.prefs?.expandThinking
                ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
            val expandTools by (container.prefs?.expandTools
                ?: kotlinx.coroutines.flow.MutableStateFlow(false)).collectAsState()
            HermexTheme(themeChoice, accentColorFromHex(accent.hex)) {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalChatDisplayPrefs provides ChatDisplayPrefs(expandThinking, expandTools),
                ) {
                    val authState by container.authManager.state.collectAsState()
                    when (val state = authState) {
                        is AuthManager.State.LoggedIn -> ConnectedRoot(container, state.server)
                        else -> OnboardingScreen(onboardingViewModel)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    /** Share-target text and notification taps park in the shared draft / extras. */
    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return
        val container = (application as HermexApp).container
        when {
            intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true ->
                container.sharedDraftStore.offer(intent.getStringExtra(Intent.EXTRA_TEXT))
            intent.action == Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                container.sharedDraftStore.offer(
                    text = intent.getStringExtra(Intent.EXTRA_TEXT),
                    fileUris = listOfNotNull(uri?.toString()),
                )
            }
            intent.action == Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                container.sharedDraftStore.offer(
                    text = intent.getStringExtra(Intent.EXTRA_TEXT),
                    fileUris = uris.orEmpty().map { it.toString() },
                )
            }
            intent.hasExtra(RunNotifications.EXTRA_SESSION_ID) ->
                pendingSessionFromNotification = intent.getStringExtra(RunNotifications.EXTRA_SESSION_ID)
            intent.getBooleanExtra(com.hermexapp.android.platform.HermexWidgetProvider.EXTRA_NEW_CHAT, false) ->
                pendingNewChatFromWidget = true
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        /** Session to open when launched from a run-complete notification. */
        var pendingSessionFromNotification: String? = null

        /** Set when launched from the home-screen widget's "New chat" button. */
        var pendingNewChatFromWidget: Boolean = false
    }
}

private sealed class Screen {
    data object SessionList : Screen()
    data class Chat(val sessionId: String) : Screen()
    data class Files(val sessionId: String) : Screen()
    data class Git(val sessionId: String) : Screen()
    data class Panel(val kind: PanelKind) : Screen()
    data object Settings : Screen()
    data object Projects : Screen()

    /**
     * Wave 8 — local notes (offline-first, persisted in app-private storage).
     * Surfaced via the phone drawer and the tablet rail. Tapping a row in the
     * future opens [NoteEditor]; for now the list screen is the destination.
     */
    data object Notes : Screen()

    /**
     * Wave 8 — local prompt library (offline-first). Same storage model as
     * [Notes]. The chat composer reads from this store to offer one-tap
     * insertion via the slash-palette (Wave 8.6).
     */
    data object Prompts : Screen()
}

@Composable
private fun ConnectedRoot(container: AppContainer, server: HttpUrl) {
    val scope = rememberCoroutineScope()
    var screen by remember(server) { mutableStateOf<Screen>(Screen.SessionList) }

    val repository = remember(server) { container.sessionRepository(server) }
    val client = remember(server) { container.apiClient(server) }
    val sessionListViewModel = remember(server) {
        SessionListViewModel(
            repository = repository,
            onAuthError = container.authManager::handleApiError,
            // Surface the URL the app is configured to talk to on the
            // "JKP is unreachable" banner so a wrong URL (e.g. a stale
            // localhost on a real device) is obvious without leaving the
            // home screen. Re-read on every refresh because the user can
            // change servers via Settings → Auth.
            currentBaseUrlProvider = { container.currentBaseUrl()?.toString() },
        ).also { it.refresh() }
    }

    // A shared text or image (ACTION_SEND) becomes a fresh chat with the
    // composer prefilled (and the image uploaded + attached).
    val pendingShare by container.sharedDraftStore.pending.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(pendingShare) {
        if (pendingShare != null) {
            val content = container.sharedDraftStore.consume() ?: return@LaunchedEffect
            val sessionId = sessionListViewModel.createSessionNow() ?: return@LaunchedEffect
            sharePrefill = content.text
            shareFileUploads = content.fileUris.mapNotNull { uriString ->
                runCatching {
                    val uri = android.net.Uri.parse(uriString)
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }
                    val name = resolveDisplayName(context, uri)
                    bytes?.let { it to name }
                }.getOrNull()
            }
            screen = Screen.Chat(sessionId)
        }
    }

    // A notification tap deep-links straight into its session.
    LaunchedEffect(Unit) {
        MainActivity.pendingSessionFromNotification?.let {
            MainActivity.pendingSessionFromNotification = null
            screen = Screen.Chat(it)
        }
        // The widget's "New chat" button opens a fresh session on launch.
        if (MainActivity.pendingNewChatFromWidget) {
            MainActivity.pendingNewChatFromWidget = false
            sessionListViewModel.createSessionNow()?.let { screen = Screen.Chat(it) }
        }
    }

    // Auto-check for a newer version on every app launch. The check
    // is silent (no spinner, no modal) — the only user-visible
    // surface is a one-line Snackbar at the bottom of the session
    // list, with a tap target that opens the GitHub release page.
    // If the network fails or the response is malformed, nothing
    // is shown (the user can still tap "Check for updates" in
    // Settings for a manual retry).
    val updateChecker = remember {
        com.hermexapp.android.network.UpdateChecker(
            owner = "JesterkingLord",
            repo = "JKPHermex",
            backendBaseUrl = server.toString(),
        )
    }
    var autoUpdateResult by remember { mutableStateOf<com.hermexapp.android.network.UpdateResult?>(null) }
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    LaunchedEffect(Unit) {
        val result = updateChecker.check(context)
        if (result is com.hermexapp.android.network.UpdateResult.UpdateAvailable) {
            autoUpdateResult = result
            val outcome = snackbarHostState.showSnackbar(
                message = "JKP Mobile ${result.latestVersion} is available",
                actionLabel = "View",
                withDismissAction = true,
                duration = androidx.compose.material3.SnackbarDuration.Long,
            )
            if (outcome == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                com.hermexapp.android.features.settings.openUrlInBrowser(
                    context,
                    result.release.htmlUrl,
                )
                autoUpdateResult = null
            } else if (outcome == androidx.compose.material3.SnackbarResult.Dismissed) {
                autoUpdateResult = null
            }
        }
    }

    /**
     * Wave 5 Slice 5.6 — tablet two-pane layout. When the device has 600dp+
     * of horizontal width (`sw >= 600dp`), the session list is always pinned
     * on the left (40% width) and the current screen renders on the right
     * (60%).
     *
     * Wave 6 Slice 6.1 — every layout now begins with a permanent left sidebar
     * (always visible, ~88dp on phones / 300dp on tablets). On phones, tapping a
     * session replaces the rail with the chat; the back affordance returns to
     * the rail. On tablets (sw >= 600dp), sessions open in the right pane while
     * the rail stays visible — the existing ChatScreen-/-Files-/-Git behavior.
     */
    val configuration = LocalConfiguration.current
    val onTablet = configuration.screenWidthDp >= 600
    val sidebarWidth = if (onTablet) 300.dp else 88.dp
    // Right-pane screens that always live alongside the rail (chat, files,
    // git, project files). On the literal session list, Settings, Panels, or
    // Projects we treat the SessionListScreen itself as the right pane — the
    // "rail" only ever shows on screens where the right pane is showing a
    // chat-shaped document.
    val railScreens = screen is Screen.Chat || screen is Screen.Files || screen is Screen.Git

androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
    // The right-pane screen content lives in renderScreen(); the
    // lambda closes over `screen`/`sharePrefill`/`shareFileUploads` so
    // state changes propagate. Each renderScreen invocation receives
    // the current target via Crossfade.
    val setScreen: (Screen) -> Unit = { screen = it }
    val rightPane: @Composable () -> Unit = {
        Crossfade(targetState = screen, label = "screens") { current ->
            renderScreen(
                current = current,
                container = container,
                server = server,
                sessionListViewModel = sessionListViewModel,
                sharePrefillRef = { sharePrefill },
                consumeSharePrefill = { sharePrefill = null },
                shareFileUploadsRef = { shareFileUploads },
                consumeShareFileUploads = {
                    val u = shareFileUploads; shareFileUploads = emptyList(); u
                },
                setScreen = setScreen,
            )
        }
    }
    if (railScreens) {
        // Sidebar rail + right pane (chat/files/git).
        // Wave 6 Slice 6.1 — tablet gains a 300dp session list; phone gets an
        // 88dp Wordmark-only rail with a "Switch conversation" pill that pops
        // an overlay picker. Back from chat returns the user to the literal
        // SessionList (settings/projects/panels don't show a rail).
        //
        // Wave 8 Slice 8.1 — phone rail is upgraded to a ModalNavigationDrawer.
        // The drawer hosts all five tools (new chat, sessions, notes, prompts,
        // settings) and slides in over the chat from a ☰ button in the top-left
        // of the right pane. Tablet keeps the always-on rail verbatim — no
        // behaviour change for big screens.
        if (onTablet) {
            androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxSize()) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .width(sidebarWidth)
                        .fillMaxSize(),
                ) {
                    SessionListScreen(
                        viewModel = sessionListViewModel,
                        onOpenSession = { sid -> setScreen(Screen.Chat(sid)) },
                        onOpenPanel = { kind -> setScreen(Screen.Panel(PanelKind.valueOf(kind))) },
                        onOpenSettings = { setScreen(Screen.Settings) },
                        onOpenProjects = { setScreen(Screen.Projects) },
                    )
                }
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    rightPane()
                }
            }
        } else {
            PhoneDrawerScaffold(
                selected = drawerSelectedFor(screen),
                onSelect = { tab ->
                    onDrawerSelect(tab, setScreen, sessionListViewModel, scope)
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                // ☰ button overlayed top-left → toggles drawer. Lives at
                // compose-tree-top so it never lands inside a chat LazyColumn
                // that could eat it.
                androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
                    rightPane()
                }
            }
        }
    } else {
        // Settings / Projects / Panels / SessionList itself: full-width rail.
        // (Wave 6.2's section headers will live inside this rail.)
        rightPane()
    }
        // Auto-update Snackbar — overlaid on every screen so the user
        // sees the "new version" prompt regardless of which screen
        // they're on when the check completes.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(androidx.compose.ui.Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}

/**
 * Single-screen render for [Crossfade] (used in both single-pane and as the
 * right pane in two-pane tablet layout). Keeps ConnectedRoot's screen
 * switching logic in one place.
 *
 * Reads everything it needs from the captured values + `container`/`server`
 * thread-through; the `setScreen` callback closes over the parent's
 * `screen` mutableState.
 */
@Composable
private fun renderScreen(
    current: Screen,
    container: com.hermexapp.android.AppContainer,
    server: HttpUrl,
    sessionListViewModel: SessionListViewModel,
    sharePrefillRef: () -> String?,
    consumeSharePrefill: () -> Unit,
    shareFileUploadsRef: () -> List<Pair<ByteArray, String>>,
    consumeShareFileUploads: () -> List<Pair<ByteArray, String>>,
    setScreen: (Screen) -> Unit,
) {
    val client = remember(server) { container.apiClient(server) }
    val repository = remember(server) { container.sessionRepository(server) }
    val scope = rememberCoroutineScope()
    when (current) {
        Screen.SessionList -> SessionListScreen(
            viewModel = sessionListViewModel,
            onOpenSession = { sid -> setScreen(Screen.Chat(sid)) },
            onOpenPanel = { kind -> setScreen(Screen.Panel(PanelKind.valueOf(kind))) },
            onOpenSettings = { setScreen(Screen.Settings) },
            onOpenProjects = { setScreen(Screen.Projects) },
        )
        Screen.Projects -> {
            BackHandler { setScreen(Screen.SessionList) }
            com.hermexapp.android.features.sessionlist.ProjectsScreen(
                viewModel = sessionListViewModel,
                onOpenSession = { sid -> setScreen(Screen.Chat(sid)) },
                onClose = { setScreen(Screen.SessionList) },
            )
        }
        is Screen.Chat -> {
            val appContext = LocalContext.current.applicationContext
            val chatViewModel = remember(server, current.sessionId) {
                ChatViewModel(
                    sessionId = current.sessionId,
                    repository = repository,
                    client = client,
                    sse = container.sseClient(),
                    prefs = container.prefs,
                    onAuthError = container.authManager::handleApiError,
                ).also { vm ->
                    sharePrefillRef()?.let { vm.updateComposerText(it); consumeSharePrefill() }
                    if (shareFileUploadsRef().isNotEmpty()) {
                        val uploads = consumeShareFileUploads()
                        vm.viewModelScope.launch {
                            uploads.forEach { (bytes, name) -> vm.addAttachmentNow(bytes, name) }
                        }
                    }
                }
            }
            val chatState by chatViewModel.uiState.collectAsState()
            // Ongoing-run foreground service: alive only while streaming.
            LaunchedEffect(chatState.isStreaming) {
                if (chatState.isStreaming) {
                    com.hermexapp.android.platform.ActiveRunService.start(appContext, chatState.title)
                } else {
                    com.hermexapp.android.platform.ActiveRunService.stop(appContext)
                }
            }
            // Wave 8.5 — long-press on the chat send button opens the
            // insert palette (notes + prompts). The chat VM lives at
            // this scope so the picked text can update it directly via
            // setComposerText when the user dismisses the sheet.
            var insertSheetOpen by remember { mutableStateOf(false) }
            // Wave 9 — template sheet opened by the "Templates" chip.
            var templatesSheetOpen by remember { mutableStateOf(false) }
            val paletteVm = remember {
                com.hermexapp.android.features.composer.InsertPaletteViewModel.Factory(
                    noteStore = container.noteStore,
                    promptStore = container.promptStore,
                ).create(com.hermexapp.android.features.composer.InsertPaletteViewModel::class.java)
            }
            // Single back-gesture handler closes whichever chip-opened
            // sheet is up. Falls through to chat back when none are open.
            val anySheetOpen = insertSheetOpen || templatesSheetOpen
            BackHandler(enabled = anySheetOpen) {
                insertSheetOpen = false
                templatesSheetOpen = false
            }
            BackHandler { setScreen(Screen.SessionList) }
            ChatScreen(
                viewModel = chatViewModel,
                onBack = { setScreen(Screen.SessionList) },
                onOpenFiles = { setScreen(Screen.Files(current.sessionId)) },
                onOpenGit = { setScreen(Screen.Git(current.sessionId)) },
                onRunFinished = { title ->
                    if (container.prefs?.notificationsEnabled?.value != false) {
                        container.notifications?.notifyRunComplete(title, current.sessionId)
                    }
                },
                onLongPressSend = { insertSheetOpen = true },
                // Wave 9: "Improve" — wrap the current draft in a polish
                // instruction, hit send. We use the existing send pipeline
                // rather than carving a separate "rewrite" endpoint because
                // the chat model is already the one driving quality.
                onImproveDraft = {
                    val draft = chatViewModel.uiState.value.composerText
                    if (draft.isBlank()) {
                        // No-op if the composer is empty; the rail already
                        // hides in that case so we shouldn't be here, but
                        // be defensive against race recompositions.
                    } else {
                        chatViewModel.updateComposerText(
                            "Improve this prompt. Return only the improved version, no preamble:\n\n$draft",
                        )
                        chatViewModel.send()
                    }
                },
                onOpenTemplates = { templatesSheetOpen = true },
                // Open the insert palette but pre-narrow the view-model's
                // filter so the user only sees prompts (vs. notes).
                onInsertFromNotes = {
                    paletteVm.setFilter(InsertPaletteViewModel.Filter.NOTES)
                    insertSheetOpen = true
                },
                onInsertFromPrompts = {
                    paletteVm.setFilter(InsertPaletteViewModel.Filter.PROMPTS)
                    insertSheetOpen = true
                },
            )
            if (insertSheetOpen) {
                com.hermexapp.android.features.composer.InsertPaletteSheet(
                    viewModel = paletteVm,
                    onPick = { body ->
                        chatViewModel.setComposerText(body)
                        insertSheetOpen = false
                    },
                    onDismiss = { insertSheetOpen = false },
                )
            }
            if (templatesSheetOpen) {
                com.hermexapp.android.features.composer.ComposerTemplatesSheet(
                    onPick = { body -> chatViewModel.setComposerText(body) },
                    onDismiss = { templatesSheetOpen = false },
                )
            }
        }
        is Screen.Files -> {
            val workspaceViewModel = remember(server, current.sessionId, "files") {
                WorkspaceViewModel(current.sessionId, client, container.authManager::handleApiError)
            }
            FileBrowserScreen(
                viewModel = workspaceViewModel,
                onClose = { setScreen(Screen.Chat(current.sessionId)) },
            )
        }
        is Screen.Git -> {
            val workspaceViewModel = remember(server, current.sessionId, "git") {
                WorkspaceViewModel(current.sessionId, client, container.authManager::handleApiError)
            }
            GitScreen(
                viewModel = workspaceViewModel,
                onClose = { setScreen(Screen.Chat(current.sessionId)) },
            )
        }
        is Screen.Panel -> {
            val panelsViewModel = remember(server, current.kind) {
                PanelsViewModel(client, container.authManager::handleApiError)
            }
            BackHandler { setScreen(Screen.SessionList) }
            PanelScreen(
                kind = current.kind,
                viewModel = panelsViewModel,
                onClose = { setScreen(Screen.SessionList) },
            )
        }
        Screen.Settings -> {
            if (container.prefs == null) return
            BackHandler { setScreen(Screen.SessionList) }
            SettingsScreen(
                client = client,
                prefs = container.prefs,
                serverUrl = server.toString(),
                onSignOut = { scope.launch { container.authManager.signOut() } },
                onClose = { setScreen(Screen.SessionList) },
                registry = container.serverRegistry,
                onSwitchServer = { container.authManager.switchTo(it) },
                onAddServer = { container.authManager.beginAddServer() },
                onForgetServer = { scope.launch { container.authManager.forgetServer(it) } },
            )
        }
        Screen.Notes -> {
            BackHandler { setScreen(Screen.SessionList) }
            val notesVm = remember {
                com.hermexapp.android.features.notes.NotesViewModel.Factory(container.noteStore)
                    .create(com.hermexapp.android.features.notes.NotesViewModel::class.java)
            }
            com.hermexapp.android.features.notes.NotesScreen(
                viewModel = notesVm,
                onClose = { setScreen(Screen.SessionList) },
                // Wave 9 (AI Notes): tapping 🤖 Implement on a note drops
                // the user into a brand-new chat with the composer
                // pre-filled with the implementation prompt. We use the
                // existing `sharePrefill` channel so the chat screen
                // adopts it on next mount, then navigate to a chat on
                // the (about-to-be-created) session. Note this currently
                // sends the *existing* new-chat session id; if a
                // session is needed, the sessionList VM still has to
                // allocate a new id — for now we re-use the empty
                // "new chat" path the SessionList already exposes.
                onImplementNote = { note ->
                    val prompt = notesVm.buildImplementationPrompt(note)
                    sharePrefill = prompt
                    // Re-use the "next chat" navigation by routing through
                    // SessionList's "new chat" entry point.
                    MainActivity.pendingNewChatFromWidget = true
                    container.sharedDraftStore.offer(prompt)
                    setScreen(Screen.SessionList)
                },
            )
        }
        Screen.Prompts -> {
            BackHandler { setScreen(Screen.SessionList) }
            val promptsVm = remember {
                com.hermexapp.android.features.prompts.PromptsViewModel.Factory(container.promptStore)
                    .create(com.hermexapp.android.features.prompts.PromptsViewModel::class.java)
            }
            com.hermexapp.android.features.prompts.PromptsScreen(
                viewModel = promptsVm,
                onClose = { setScreen(Screen.SessionList) },
            )
        }
    }
}

/** Composer prefill + file handoff from a share, consumed on the next chat open. */
private var sharePrefill: String? = null
private var shareFileUploads: List<Pair<ByteArray, String>> = emptyList()

/**
 * drawerSelectedFor — translates the active [Screen] into the highlighted
 * drawer tile. Lives at file scope so both ConnectedRoot and the renderScreen
 * tests can reach it without exposing the private `Screen` type.
 */
private fun drawerSelectedFor(screen: Screen): MainScreenTab = when (screen) {
    Screen.SessionList -> MainScreenTab.Sessions
    is Screen.Chat -> MainScreenTab.Sessions
    is Screen.Files -> MainScreenTab.Sessions
    is Screen.Git -> MainScreenTab.Sessions
    is Screen.Panel -> MainScreenTab.Sessions
    Screen.Settings -> MainScreenTab.Settings
    Screen.Notes -> MainScreenTab.Notes
    Screen.Prompts -> MainScreenTab.Prompts
    Screen.Projects -> MainScreenTab.Sessions
}

/**
 * onDrawerSelect — handler for a tap in the phone drawer. NewChat requires a
 * suspending session-create API call, so the parent must invoke this from a
 * coroutine scope; the wrapper below absorbs that.
 */
private fun onDrawerSelect(
    tab: MainScreenTab,
    setScreen: (Screen) -> Unit,
    sessionListViewModel: SessionListViewModel,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    when (tab) {
        MainScreenTab.NewChat -> {
            scope.launch {
                sessionListViewModel.createSessionNow()?.let { sid ->
                    setScreen(Screen.Chat(sid))
                }
            }
        }
        MainScreenTab.Sessions -> setScreen(Screen.SessionList)
        MainScreenTab.Notes -> setScreen(Screen.Notes)
        MainScreenTab.Prompts -> setScreen(Screen.Prompts)
        MainScreenTab.Settings -> setScreen(Screen.Settings)
    }
}

/** Best-effort human filename for a shared content URI (falls back to a guess). */
private fun resolveDisplayName(context: android.content.Context, uri: android.net.Uri): String {
    if (uri.scheme == "content") {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "shared-file"
}
