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

suspend fun ApiClient.gitInfo(sessionId: String): GitInfoResponse =
    getJson(Endpoint.GIT_INFO, mapOf("session_id" to sessionId))

suspend fun ApiClient.gitStatus(sessionId: String): GitStatusResponse =
    getJson(Endpoint.GIT_STATUS, mapOf("session_id" to sessionId))

suspend fun ApiClient.gitBranches(sessionId: String): GitBranchesResponse =
    getJson(Endpoint.GIT_BRANCHES, mapOf("session_id" to sessionId))

suspend fun ApiClient.gitDiff(sessionId: String, path: String, kind: String): GitDiffResponse =
    getJson(Endpoint.GIT_DIFF, mapOf("session_id" to sessionId, "path" to path, "kind" to kind))
