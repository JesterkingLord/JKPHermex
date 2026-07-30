package com.hermexapp.android.features.panels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermexapp.android.model.CronJob
import com.hermexapp.android.model.InsightsResponse
import com.hermexapp.android.model.MemoryResponse
import com.hermexapp.android.model.SkillDetailResponse
import com.hermexapp.android.model.SkillSummary
import com.hermexapp.android.network.ApiClient
import com.hermexapp.android.network.ApiError
import com.hermexapp.android.network.crons
import com.hermexapp.android.network.insights
import com.hermexapp.android.network.memory
import com.hermexapp.android.network.pauseCron
import com.hermexapp.android.network.resumeCron
import com.hermexapp.android.network.createCron
import com.hermexapp.android.network.deleteCron
import com.hermexapp.android.network.runCron
import com.hermexapp.android.network.updateCron
import com.hermexapp.android.network.skillContent
import com.hermexapp.android.network.skills
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 7: the read-mostly server panels — Tasks (cron jobs with
 * run/pause/resume), Skills (browse + content), Memory, and Insights.
 * Mirrors the iOS panel view models; cron create/edit is a deferred slice.
 */
class PanelsViewModel(
    private val client: ApiClient,
    private val onAuthError: (Throwable) -> Unit = {},
) : ViewModel() {

    data class UiState(
        val cronJobs: List<CronJob> = emptyList(),
        val skills: List<SkillSummary> = emptyList(),
        val openSkill: SkillDetailResponse? = null,
        val memory: MemoryResponse? = null,
        val insights: InsightsResponse? = null,
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
        val noticeMessage: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun loadTasks() = launchLoad {
        val jobs = client.crons().jobs.orEmpty()
        _uiState.update { it.copy(cronJobs = jobs, isLoading = false) }
    }

    fun createCronJob(prompt: String, schedule: String, name: String?) = cronAction {
        val r = client.createCron(prompt = prompt, schedule = schedule, name = name?.takeIf { it.isNotBlank() })
        _uiState.update { it.copy(noticeMessage = r.error ?: "Task created.") }
    }

    fun updateCronJob(jobId: String, prompt: String?, schedule: String?, name: String?) = cronAction {
        val r = client.updateCron(
            jobId = jobId,
            prompt = prompt?.takeIf { it.isNotBlank() },
            schedule = schedule?.takeIf { it.isNotBlank() },
            name = name?.takeIf { it.isNotBlank() },
        )
        _uiState.update { it.copy(noticeMessage = r.error ?: "Task updated.") }
    }

    fun deleteCronJob(jobId: String) = cronAction { client.deleteCron(jobId) }

    fun runCronJob(jobId: String) = cronAction { client.runCron(jobId).also { r ->
        _uiState.update { it.copy(noticeMessage = r.error ?: "Job started.") }
    } }

    fun pauseCronJob(jobId: String) = cronAction { client.pauseCron(jobId) }

    fun resumeCronJob(jobId: String) = cronAction { client.resumeCron(jobId) }

    fun loadSkills() = launchLoad {
        val skills = client.skills().skills.orEmpty()
            .sortedWith(compareBy({ it.category?.lowercase() ?: "" }, { it.name?.lowercase() ?: "" }))
        _uiState.update { it.copy(skills = skills, isLoading = false) }
    }

    fun openSkill(name: String) = launchLoad {
        val detail = client.skillContent(name)
        _uiState.update { it.copy(openSkill = detail, isLoading = false) }
    }

    fun closeSkill() = _uiState.update { it.copy(openSkill = null) }

    fun loadMemory() = launchLoad {
        val memory = client.memory()
        _uiState.update { it.copy(memory = memory, isLoading = false) }
    }

    fun loadInsights(days: Int = 30) = launchLoad {
        val insights = client.insights(days)
        _uiState.update { it.copy(insights = insights, isLoading = false) }
    }

    private fun cronAction(action: suspend () -> Any?) {
        viewModelScope.launch {
            try {
                action()
                val jobs = client.crons().jobs.orEmpty()
                _uiState.update { it.copy(cronJobs = jobs) }
            } catch (e: ApiError) {
                onAuthError(e)
                _uiState.update { it.copy(errorMessage = e.userMessage) }
            }
        }
    }

    private fun launchLoad(block: suspend () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, noticeMessage = null) }
            try {
                block()
            } catch (e: ApiError) {
                onAuthError(e)
                _uiState.update { it.copy(errorMessage = e.userMessage, isLoading = false) }
            }
        }
    }
}
