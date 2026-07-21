package com.hermexapp.android.network

import com.hermexapp.android.model.CronJobsResponse
import com.hermexapp.android.model.CronMutationResponse
import com.hermexapp.android.model.InsightsResponse
import com.hermexapp.android.model.MemoryResponse
import com.hermexapp.android.model.SkillDetailResponse
import com.hermexapp.android.model.SkillsResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

// Server-panel endpoints, mirroring iOS `APIClient+Cron` / `+Skills` /
// `+Memory` / the insights fetch.

suspend fun ApiClient.crons(): CronJobsResponse = getJson(Endpoint.CRONS)

suspend fun ApiClient.createCron(
    prompt: String,
    schedule: String,
    name: String? = null,
    deliver: String? = null,
    skills: List<String> = emptyList(),
    model: String? = null,
    profile: String? = null,
    toastNotifications: Boolean = false,
): CronMutationResponse = postJson(
    Endpoint.CRON_CREATE,
    ApiJson.encodeToString(
        CronCreateRequest(prompt, schedule, name, deliver, skills, model, profile, toastNotifications),
    ),
)

suspend fun ApiClient.updateCron(
    jobId: String,
    prompt: String? = null,
    schedule: String? = null,
    name: String? = null,
): CronMutationResponse = postJson(
    Endpoint.CRON_UPDATE,
    ApiJson.encodeToString(CronUpdateRequest(jobId, prompt, schedule, name)),
)

suspend fun ApiClient.runCron(jobId: String): CronMutationResponse =
    postJson(Endpoint.CRON_RUN, ApiJson.encodeToString(CronJobIdRequest(jobId)))

suspend fun ApiClient.pauseCron(jobId: String): CronMutationResponse =
    postJson(Endpoint.CRON_PAUSE, ApiJson.encodeToString(CronJobIdRequest(jobId)))

suspend fun ApiClient.resumeCron(jobId: String): CronMutationResponse =
    postJson(Endpoint.CRON_RESUME, ApiJson.encodeToString(CronJobIdRequest(jobId)))

suspend fun ApiClient.deleteCron(jobId: String): CronMutationResponse =
    postJson(Endpoint.CRON_DELETE, ApiJson.encodeToString(CronJobIdRequest(jobId)))

suspend fun ApiClient.skills(): SkillsResponse = getJson(Endpoint.SKILLS)

suspend fun ApiClient.skillContent(name: String, file: String? = null): SkillDetailResponse {
    val query = buildMap {
        put("name", name)
        file?.let { put("file", it) }
    }
    return getJson(Endpoint.SKILL_CONTENT, query)
}

suspend fun ApiClient.memory(): MemoryResponse = getJson(Endpoint.MEMORY)

suspend fun ApiClient.insights(days: Int = 30): InsightsResponse =
    getJson(Endpoint.INSIGHTS, mapOf("days" to days.toString()))

@Serializable
private data class CronJobIdRequest(@SerialName("job_id") val jobId: String)

@Serializable
private data class CronCreateRequest(
    val prompt: String,
    val schedule: String,
    val name: String? = null,
    val deliver: String? = null,
    val skills: List<String> = emptyList(),
    val model: String? = null,
    val profile: String? = null,
    @SerialName("toast_notifications") val toastNotifications: Boolean = false,
)

@Serializable
private data class CronUpdateRequest(
    @SerialName("job_id") val jobId: String,
    val prompt: String? = null,
    val schedule: String? = null,
    val name: String? = null,
)
