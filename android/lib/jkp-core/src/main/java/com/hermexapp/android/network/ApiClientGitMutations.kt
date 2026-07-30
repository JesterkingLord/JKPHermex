package com.hermexapp.android.network

import com.hermexapp.android.model.GitCheckoutResponse
import com.hermexapp.android.model.GitCommitResponse
import com.hermexapp.android.model.GitMutationResponse
import com.hermexapp.android.model.GitRemoteActionResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

// Git write endpoints, mirroring the iOS APIClient+Git request bodies.

suspend fun ApiClient.gitStage(sessionId: String, paths: List<String>): GitMutationResponse =
    postJson(Endpoint.GIT_STAGE, ApiJson.encodeToString(GitPathsRequest(sessionId, paths)))

suspend fun ApiClient.gitUnstage(sessionId: String, paths: List<String>): GitMutationResponse =
    postJson(Endpoint.GIT_UNSTAGE, ApiJson.encodeToString(GitPathsRequest(sessionId, paths)))

suspend fun ApiClient.gitDiscard(
    sessionId: String,
    paths: List<String>,
    deleteUntracked: Boolean = false,
): GitMutationResponse = postJson(
    Endpoint.GIT_DISCARD,
    ApiJson.encodeToString(GitDiscardRequest(sessionId, paths, deleteUntracked)),
)

suspend fun ApiClient.gitCommit(sessionId: String, message: String): GitCommitResponse =
    postJson(Endpoint.GIT_COMMIT, ApiJson.encodeToString(GitCommitRequest(sessionId, message)))

suspend fun ApiClient.gitCheckout(sessionId: String, ref: String): GitCheckoutResponse =
    postJson(Endpoint.GIT_CHECKOUT, ApiJson.encodeToString(GitCheckoutRequest(sessionId, ref, "switch")))

suspend fun ApiClient.gitFetch(sessionId: String): GitRemoteActionResponse =
    postJson(Endpoint.GIT_FETCH, ApiJson.encodeToString(SessionIdBody(sessionId)))

suspend fun ApiClient.gitPull(sessionId: String): GitRemoteActionResponse =
    postJson(Endpoint.GIT_PULL, ApiJson.encodeToString(SessionIdBody(sessionId)))

suspend fun ApiClient.gitPush(sessionId: String): GitRemoteActionResponse =
    postJson(Endpoint.GIT_PUSH, ApiJson.encodeToString(SessionIdBody(sessionId)))

@Serializable
private data class GitPathsRequest(
    @SerialName("session_id") val sessionId: String,
    val paths: List<String>,
)

@Serializable
private data class GitDiscardRequest(
    @SerialName("session_id") val sessionId: String,
    val paths: List<String>,
    @SerialName("delete_untracked") val deleteUntracked: Boolean,
)

@Serializable
private data class GitCommitRequest(
    @SerialName("session_id") val sessionId: String,
    val message: String,
)

@Serializable
private data class GitCheckoutRequest(
    @SerialName("session_id") val sessionId: String,
    val ref: String,
    val mode: String,
)
