package com.hermexapp.android.features.sessionlist

import com.hermexapp.android.model.SessionDetail
import com.hermexapp.android.model.SessionSummary
import com.hermexapp.android.model.SessionsResponse
import com.hermexapp.android.network.ApiClient
import com.hermexapp.android.network.ApiError
import com.hermexapp.android.network.ApiJson
import com.hermexapp.android.network.archiveSession
import com.hermexapp.android.network.branchSession
import com.hermexapp.android.network.createSession
import com.hermexapp.android.network.deleteSession
import com.hermexapp.android.network.duplicateSession
import com.hermexapp.android.network.moveSession
import com.hermexapp.android.network.createProject
import com.hermexapp.android.network.deleteProject
import com.hermexapp.android.network.pinSession
import com.hermexapp.android.network.projects
import com.hermexapp.android.network.renameProject
import com.hermexapp.android.network.renameSession
import com.hermexapp.android.network.searchSessions
import com.hermexapp.android.network.session
import com.hermexapp.android.network.sessions
import com.hermexapp.android.persistence.CacheStore
import kotlinx.serialization.encodeToString

/**
 * Exposes the operations [SessionListViewModel] and [ChatViewModel] (via
 * `loadSession`) need. Defined so the VM is unit-testable with a fake — see
 * `SessionRepositoryTest.kt` for the network-level test and the per-test
 * `FakeSessionRepository` in [com.hermexapp.android.features.sessionlist.SessionListViewModelTest].
 *
 * Wave 0 split: leaving this as an `interface SessionRepository` rather than
 * a concrete class lets us keep the production network path unchanged while
 * making the new bulk-selection tests pure-JVM.
 */
interface SessionRepository {
    data class SessionsResult(val sessions: List<SessionSummary>, val fromCache: Boolean)

    suspend fun loadSessions(): SessionsResult

    suspend fun search(query: String): List<SessionSummary>

    suspend fun loadSession(id: String): Pair<SessionDetail?, Boolean>

    suspend fun createSession(): SessionDetail?

    suspend fun renameSession(id: String, title: String): com.hermexapp.android.model.SessionMutationResponse

    suspend fun deleteSession(id: String): com.hermexapp.android.model.SessionMutationResponse

    suspend fun pinSession(id: String, pinned: Boolean): com.hermexapp.android.model.SessionMutationResponse

    suspend fun archiveSession(id: String, archived: Boolean): com.hermexapp.android.model.SessionMutationResponse

    suspend fun duplicateSession(id: String): SessionDetail?

    suspend fun moveSession(id: String, projectId: String?): com.hermexapp.android.model.SessionMutationResponse

    /**
     * No-default form: explicit `keepCount` and `title`. The interface
     * declaration deliberately omits defaults — Kotlin doesn't allow
     * override-method defaults to differ from the parent signature, and
     * a single-source default could be luring callers into silently
     * different behaviour at the implementation boundary.
     *
     * For convenience, `branchSession(id)` with no extra args is also part
     * of this contract: implementations MUST support it as a synonym for
     * `branchSession(id, null, null)` (matches the historical single-arg
     * call site used by [SessionListViewModel]).
     */
    suspend fun branchSession(
        id: String,
        keepCount: Int?,
        title: String?,
    ): com.hermexapp.android.model.SessionBranchResponse

    /** Convenience overload that forks with the full history and the default title. */
    suspend fun branchSession(id: String): com.hermexapp.android.model.SessionBranchResponse

    suspend fun loadProjects(): List<com.hermexapp.android.model.Project>

    suspend fun createProject(name: String, color: String?): com.hermexapp.android.model.ProjectMutationResponse

    suspend fun renameProject(id: String, name: String, color: String?): com.hermexapp.android.model.ProjectMutationResponse

    suspend fun deleteProject(id: String): com.hermexapp.android.model.ProjectMutationResponse
}

/**
 * Network-aware [SessionRepository]. The interface above is the testable
 * shape; this concrete class is the production plumbing.
 *
 * Sessions with an offline read path (plan phase 3): network responses are
 * cached as raw JSON per server host; when the network fails, the last cached
 * copy is served with an `offline` marker so the UI can say so. Mirrors the
 * intent of the iOS `CacheFallbackPolicy` + SwiftData cache in one seam.
 */
class SessionRepositoryImpl(
    private val client: ApiClient,
    private val cache: CacheStore,
) : SessionRepository {
    private val host: String get() = client.baseUrl.host

    /** Network first; on failure fall back to cache; rethrow when neither works. */
    override suspend fun loadSessions(): SessionRepository.SessionsResult {
        val response = try {
            client.sessions()
        } catch (e: ApiError) {
            // Session expiry must surface (it changes auth state), never be
            // masked by a stale cache.
            if (e is ApiError.Unauthorized) throw e
            val cached = cache.load(CacheStore.sessionsKey(host)) ?: throw e
            val decoded = try {
                ApiJson.decodeFromString<SessionsResponse>(cached)
            } catch (_: Exception) {
                throw e
            }
            return com.hermexapp.android.features.sessionlist.SessionRepository.SessionsResult(sort(decoded.sessions.orEmpty()), fromCache = true)
        }

        cache.save(CacheStore.sessionsKey(host), ApiJson.encodeToString(response))
        return com.hermexapp.android.features.sessionlist.SessionRepository.SessionsResult(sort(response.sessions.orEmpty()), fromCache = false)
    }

    /** Server-side search (`/api/sessions/search`) — network only, like iOS. */
    override suspend fun search(query: String): List<SessionSummary> =
        sort(client.searchSessions(query).sessions.orEmpty())

    /** Session detail incl. transcript; cached for offline reopening. */
    override suspend fun loadSession(id: String): Pair<SessionDetail?, Boolean> {
        val key = CacheStore.sessionKey(host, id)
        val response = try {
            client.session(id)
        } catch (e: ApiError) {
            if (e is ApiError.Unauthorized) throw e
            val cached = cache.load(key) ?: throw e
            val decoded = try {
                ApiJson.decodeFromString<com.hermexapp.android.model.SessionResponse>(cached)
            } catch (_: Exception) {
                throw e
            }
            return decoded.session to true
        }

        cache.save(key, ApiJson.encodeToString(response))
        return response.session to false
    }

    override suspend fun createSession(): SessionDetail? = client.createSession().session

    override suspend fun renameSession(id: String, title: String) = client.renameSession(id, title)

    override suspend fun deleteSession(id: String) = client.deleteSession(id)

    override suspend fun pinSession(id: String, pinned: Boolean) = client.pinSession(id, pinned)

    override suspend fun archiveSession(id: String, archived: Boolean) =
        client.archiveSession(id, archived)

    override suspend fun duplicateSession(id: String): SessionDetail? =
        client.duplicateSession(id).session

    override suspend fun moveSession(id: String, projectId: String?) = client.moveSession(id, projectId)

    override suspend fun branchSession(
        id: String,
        keepCount: Int?,
        title: String?,
    ): com.hermexapp.android.model.SessionBranchResponse =
        client.branchSession(id, keepCount, title)

    /** Convenience overload that keeps the historical `branchSession(id)` signature. */
    override suspend fun branchSession(id: String): com.hermexapp.android.model.SessionBranchResponse =
        branchSession(id, null, null)

    /** Projects (folders) for the active profile. Network only, like the list. */
    override suspend fun loadProjects(): List<com.hermexapp.android.model.Project> =
        client.projects().projects.orEmpty()

    override suspend fun createProject(
        name: String,
        color: String?,
    ): com.hermexapp.android.model.ProjectMutationResponse =
        client.createProject(name, color)

    override suspend fun renameProject(
        id: String,
        name: String,
        color: String?,
    ): com.hermexapp.android.model.ProjectMutationResponse =
        client.renameProject(id, name, color)

    override suspend fun deleteProject(id: String): com.hermexapp.android.model.ProjectMutationResponse =
        client.deleteProject(id)

    /** Pinned first, then most recent activity — matches the sidebar ordering. */
    private fun sort(sessions: List<SessionSummary>): List<SessionSummary> =
        sessions.sortedWith(
            compareByDescending<SessionSummary> { it.pinned == true }
                .thenByDescending { it.lastMessageAt ?: it.updatedAt ?: it.createdAt ?: 0.0 },
        )
}
