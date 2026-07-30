package com.hermexapp.android.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Server-panel shapes mirroring the iOS Cron.swift / Skills.swift / Memory.swift /
// InsightsModels.swift models. Everything nullable + tolerant (hard rule #3).

/** `GET /api/crons`. */
@Serializable
data class CronJobsResponse(val jobs: List<CronJob>? = null)

@Serializable
data class CronJob(
    @SerialName("job_id") val jobId: String? = null,
    val name: String? = null,
    val prompt: String? = null,
    // Raw: upstream sends a structured schedule object; the display string is
    // what the panel renders.
    val schedule: JsonElement? = null,
    @SerialName("schedule_display") val scheduleDisplay: String? = null,
    val enabled: Boolean? = null,
    val state: String? = null,
    @SerialName("last_status") val lastStatus: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    val model: String? = null,
    val profile: String? = null,
) {
    val stableId: String get() = jobId ?: name ?: "cron-${hashCode()}"
}

/** Cron mutations (`run`/`pause`/`resume`/`delete`). */
@Serializable
data class CronMutationResponse(
    val ok: Boolean? = null,
    val job: CronJob? = null,
    val error: String? = null,
)

/** `GET /api/skills`. */
@Serializable
data class SkillsResponse(val skills: List<SkillSummary>? = null)

@Serializable
data class SkillSummary(
    val name: String? = null,
    val category: String? = null,
    val description: String? = null,
    val path: String? = null,
)

/** `GET /api/skills/content?name=…`. */
@Serializable
data class SkillDetailResponse(
    val name: String? = null,
    val content: String? = null,
    @SerialName("linked_files") val linkedFiles: List<String>? = null,
)

/** `GET /api/memory`. */
@Serializable
data class MemoryResponse(
    val memory: String? = null,
    val user: String? = null,
    val soul: String? = null,
    @SerialName("memory_path") val memoryPath: String? = null,
    @SerialName("user_path") val userPath: String? = null,
    @SerialName("soul_path") val soulPath: String? = null,
)

/** `GET /api/insights?days=…`. */
@Serializable
data class InsightsResponse(
    @SerialName("period_days") val periodDays: Int? = null,
    @SerialName("total_sessions") val totalSessions: Int? = null,
    @SerialName("total_messages") val totalMessages: Int? = null,
    @SerialName("total_input_tokens") val totalInputTokens: Int? = null,
    @SerialName("total_output_tokens") val totalOutputTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
    @SerialName("total_cost") val totalCost: Double? = null,
    val models: List<InsightsModelBreakdown>? = null,
)

@Serializable
data class InsightsModelBreakdown(
    val model: String? = null,
    val sessions: Int? = null,
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
    val cost: Double? = null,
)

/** Session mutations (`rename`/`delete`/`pin`/`archive`). */
@Serializable
data class SessionMutationResponse(
    val ok: Boolean? = null,
    val session: SessionSummary? = null,
    val error: String? = null,
)
