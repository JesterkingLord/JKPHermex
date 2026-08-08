package com.hermexapp.android.network

import com.hermexapp.android.model.DirectoryListResponse
import com.hermexapp.android.model.FileResponse
import com.hermexapp.android.model.GitBranchesResponse
import com.hermexapp.android.model.GitDiffResponse
import com.hermexapp.android.model.GitInfoResponse
import com.hermexapp.android.model.GitStatusResponse

// Workspace-browser + read-only git endpoints, mirroring iOS
// `APIClient+Workspace` / `APIClient+Git`.

suspend fun ApiClient.directoryList(sessionId: String, path: String? = null): DirectoryListResponse {
    val query = buildMap {
        put("session_id", sessionId)
        path?.let { put("path", it) }
    }
    return getJson(Endpoint.DIRECTORY_LIST, query)
}

suspend fun ApiClient.file(sessionId: String, path: String): FileResponse =
    getJson(Endpoint.FILE, mapOf("session_id" to sessionId, "path" to path))

/**
 * Raw bytes of the file at [absolutePath], for previewing what `/api/file`
 * cannot carry.
 *
 * `/api/file` returns `raw.decode('utf-8', errors='replace')`, so an image read
 * through it arrives as replacement characters — a screenful of mojibake rather
 * than a picture, and with no error to say why.
 *
 * The path must be **absolute**: `/api/media` takes no `session_id` and answers
 * a relative path with 403. Build it with `workspaceAbsolutePath`, and skip the
 * call entirely when the workspace root is unknown rather than spending a round
 * trip that can only fail.
 *
 * Capped, because the endpoint is not: `/api/file` refuses anything over
 * 400,000 bytes, but `/api/media` served a 3 MB file whole when probed and
 * would do the same for a 500 MB one. Over the ceiling the call fails with
 * [ApiError.TooLarge] rather than allocating it.
 */
suspend fun ApiClient.mediaBytes(
    absolutePath: String,
    maxBytes: Long = ApiClient.DEFAULT_MAX_DOWNLOAD_BYTES,
): ByteArray = getBytes(Endpoint.MEDIA, mapOf("path" to absolutePath), maxBytes)

suspend fun ApiClient.gitInfo(sessionId: String): GitInfoResponse =
    getJson(Endpoint.GIT_INFO, mapOf("session_id" to sessionId))

suspend fun ApiClient.gitStatus(sessionId: String): GitStatusResponse =
    getJson(Endpoint.GIT_STATUS, mapOf("session_id" to sessionId))

suspend fun ApiClient.gitBranches(sessionId: String): GitBranchesResponse =
    getJson(Endpoint.GIT_BRANCHES, mapOf("session_id" to sessionId))

suspend fun ApiClient.gitDiff(sessionId: String, path: String, kind: String): GitDiffResponse =
    getJson(Endpoint.GIT_DIFF, mapOf("session_id" to sessionId, "path" to path, "kind" to kind))
