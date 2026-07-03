package com.hermexapp.android.features.panels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class PanelKind(val title: String) {
    TASKS("Tasks"),
    SKILLS("Skills"),
    MEMORY("Memory"),
    INSIGHTS("Insights"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PanelScreen(kind: PanelKind, viewModel: PanelsViewModel, onClose: () -> Unit) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(kind) {
        when (kind) {
            PanelKind.TASKS -> viewModel.loadTasks()
            PanelKind.SKILLS -> viewModel.loadSkills()
            PanelKind.MEMORY -> viewModel.loadMemory()
            PanelKind.INSIGHTS -> viewModel.loadInsights()
        }
    }
    BackHandler {
        if (state.openSkill != null) viewModel.closeSkill() else onClose()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(state.openSkill?.name ?: kind.title) },
                navigationIcon = {
                    TextButton(onClick = {
                        if (state.openSkill != null) viewModel.closeSkill() else onClose()
                    }) { Text("Back") }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            state.errorMessage?.let {
                Text(
                    it,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            state.noticeMessage?.let {
                Text(
                    it,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            when {
                state.openSkill != null -> SkillDetail(state.openSkill!!)
                kind == PanelKind.TASKS -> TasksPanel(state, viewModel)
                kind == PanelKind.SKILLS -> SkillsPanel(state, viewModel)
                kind == PanelKind.MEMORY -> MemoryPanel(state)
                kind == PanelKind.INSIGHTS -> InsightsPanel(state)
            }
        }
    }
}

@Composable
private fun TasksPanel(state: PanelsViewModel.UiState, viewModel: PanelsViewModel) {
    if (state.cronJobs.isEmpty()) {
        CenteredNote("No scheduled tasks on this server.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.cronJobs, key = { it.stableId }) { job ->
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(job.name ?: job.jobId ?: "Task", style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(
                        job.scheduleDisplay,
                        job.state ?: if (job.enabled == false) "paused" else "active",
                        job.lastStatus?.let { "last: $it" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                job.lastError?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val id = job.jobId
                    if (id != null) {
                        TextButton(onClick = { viewModel.runCronJob(id) }) { Text("Run now") }
                        if (job.enabled == false || job.state == "paused") {
                            TextButton(onClick = { viewModel.resumeCronJob(id) }) { Text("Resume") }
                        } else {
                            TextButton(onClick = { viewModel.pauseCronJob(id) }) { Text("Pause") }
                        }
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun SkillsPanel(state: PanelsViewModel.UiState, viewModel: PanelsViewModel) {
    if (state.skills.isEmpty()) {
        CenteredNote("No skills installed on this server.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(state.skills, key = { it.name ?: it.path ?: it.hashCode().toString() }) { skill ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { skill.name?.let(viewModel::openSkill) }
                    .padding(16.dp),
            ) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(skill.name ?: "Skill", style = MaterialTheme.typography.titleSmall)
                    skill.category?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                skill.description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun SkillDetail(skill: com.hermexapp.android.model.SkillDetailResponse) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            skill.content ?: "This skill has no readable content.",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun MemoryPanel(state: PanelsViewModel.UiState) {
    val memory = state.memory
    if (memory == null || (memory.memory.isNullOrBlank() && memory.user.isNullOrBlank() && memory.soul.isNullOrBlank())) {
        CenteredNote("No agent memory recorded yet.")
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MemorySection("Memory", memory.memory)
        MemorySection("User", memory.user)
        MemorySection("Soul", memory.soul)
    }
}

@Composable
private fun MemorySection(title: String, content: String?) {
    if (content.isNullOrBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(content, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun InsightsPanel(state: PanelsViewModel.UiState) {
    val insights = state.insights
    if (insights == null) {
        CenteredNote("No usage data for this period.")
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Last ${insights.periodDays ?: 30} days", style = MaterialTheme.typography.titleSmall)
        StatRow("Sessions", insights.totalSessions?.toString())
        StatRow("Messages", insights.totalMessages?.toString())
        StatRow("Tokens", insights.totalTokens?.let { formatCount(it) })
        StatRow("Cost", insights.totalCost?.let { "$%.2f".format(it) })
        if (!insights.models.isNullOrEmpty()) {
            Text("By model", style = MaterialTheme.typography.titleSmall)
            insights.models.forEach { model ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        model.model ?: "?",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        listOfNotNull(
                            model.totalTokens?.let(::formatCount),
                            model.cost?.let { "$%.2f".format(it) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value ?: "—", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CenteredNote(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }
}

private fun formatCount(value: Int): String = when {
    value >= 1_000_000 -> "%.1fM".format(value / 1_000_000.0)
    value >= 1_000 -> "%.1fk".format(value / 1_000.0)
    else -> value.toString()
}
