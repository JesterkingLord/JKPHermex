package com.hermexapp.android.features.chat

import com.hermexapp.android.model.AgentCommand
import com.hermexapp.android.model.ModelCatalogGroup
import com.hermexapp.android.model.ProfileSummary
import com.hermexapp.android.model.WorkspaceRoot
import com.hermexapp.android.network.ApiClient
import com.hermexapp.android.network.commands
import com.hermexapp.android.network.models
import com.hermexapp.android.network.profiles
import com.hermexapp.android.network.workspaces
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Composer configuration: what the selectors offer and what is currently
 * picked. Mirrors the iOS `ChatComposerConfigState` in spirit — selections are
 * per-chat and ride on `/api/chat/start`.
 */
data class ComposerConfig(
    val modelGroups: List<ModelCatalogGroup> = emptyList(),
    val defaultModel: String? = null,
    val profiles: List<ProfileSummary> = emptyList(),
    val activeProfile: String? = null,
    val workspaces: List<WorkspaceRoot> = emptyList(),
    val lastWorkspace: String? = null,
    val commands: List<AgentCommand> = emptyList(),
    val selectedModelId: String? = null,
    val selectedProviderId: String? = null,
    val selectedProfile: String? = null,
    val selectedWorkspace: String? = null,
) {
    val selectedModelDisplayName: String?
        get() = modelGroups.flatMap { it.models }
            .firstOrNull { it.id == (selectedModelId ?: defaultModel) }
            ?.displayName
            ?: selectedModelId
            ?: defaultModel

    /** Slash suggestions for a composer draft starting with `/`. */
    fun slashSuggestions(draft: String): List<AgentCommand> {
        if (!draft.startsWith("/")) return emptyList()
        val token = draft.removePrefix("/").substringBefore(' ')
        if (draft.contains(' ') && token.isNotEmpty()) return emptyList()
        return commands
            .filter { it.cliOnly != true && it.gatewayOnly != true }
            .filter { command ->
                val name = command.name?.removePrefix("/") ?: return@filter false
                token.isEmpty() || name.startsWith(token, ignoreCase = true)
            }
            .take(8)
    }
}

/** One pending upload, mirroring the iOS `PendingAttachment`. */
data class PendingAttachment(
    val name: String,
    val path: String,
    val mime: String,
    val size: Int?,
    val isImage: Boolean,
) {
    /** Wire payload for `/api/chat/start` `attachments[]` — same keys as iOS `toJSONValue`. */
    fun toJsonElement(): JsonObject = JsonObject(
        buildMap {
            put("name", JsonPrimitive(name))
            put("path", JsonPrimitive(path))
            put("mime", JsonPrimitive(mime))
            // Qualified: inside buildMap the receiver's Map.size shadows the
            // attachment's size property.
            this@PendingAttachment.size?.let { put("size", JsonPrimitive(it)) }
            put("is_image", JsonPrimitive(isImage))
        },
    )

    companion object {
        const val MAX_UPLOAD_BYTES = 20 * 1_024 * 1_024

        /** Mirrors the iOS `chatMessageText`: appends the attached-files marker. */
        fun messageText(draft: String, attachments: List<PendingAttachment>): String {
            val references = attachments
                .map { it.path.ifEmpty { it.name }.trim() }
                .filter { it.isNotEmpty() }
            if (references.isEmpty()) return draft
            return "$draft\n\n[Attached files: ${references.joinToString(", ")}]"
        }
    }
}

/** Loads the four catalog fetches concurrently; individual failures degrade to empty. */
suspend fun loadComposerConfig(client: ApiClient): ComposerConfig = coroutineScope {
    val modelsDeferred = async { runCatching { client.models() }.getOrNull() }
    val profilesDeferred = async { runCatching { client.profiles() }.getOrNull() }
    val workspacesDeferred = async { runCatching { client.workspaces() }.getOrNull() }
    val commandsDeferred = async { runCatching { client.commands() }.getOrNull() }

    val models = modelsDeferred.await()
    val profiles = profilesDeferred.await()
    val workspaces = workspacesDeferred.await()
    val commands = commandsDeferred.await()

    ComposerConfig(
        modelGroups = models?.catalogGroups.orEmpty(),
        defaultModel = models?.defaultModel,
        profiles = profiles?.profiles.orEmpty(),
        activeProfile = profiles?.active,
        workspaces = workspaces?.workspaces.orEmpty(),
        lastWorkspace = workspaces?.last,
        commands = commands?.commands.orEmpty(),
    )
}
