package com.hermexapp.android.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Composer-configuration shapes mirroring the iOS ServerCatalog.swift models.

/** `GET /api/models`. Groups stay raw JSON (like the iOS `[JSONValue]`) and are
 *  parsed tolerantly by [catalogGroups] — a malformed group is skipped, never fatal. */
@Serializable
data class ModelsResponse(
    val groups: List<JsonElement>? = null,
    @SerialName("default_model") val defaultModel: String? = null,
    @SerialName("active_provider") val activeProvider: String? = null,
) {
    val catalogGroups: List<ModelCatalogGroup>
        get() = groups.orEmpty().mapIndexedNotNull { index, element ->
            val group = element as? JsonObject ?: return@mapIndexedNotNull null
            val providerId = group.stringValue("provider_id")
            val name = group.stringValue("name") ?: providerId ?: "Models"
            val models = parseOptions(group["models"], providerId)
            if (models.isEmpty()) return@mapIndexedNotNull null
            ModelCatalogGroup(
                id = providerId ?: "$name-$index",
                name = name,
                providerId = providerId,
                models = models,
            )
        }

    private fun parseOptions(value: JsonElement?, providerId: String?): List<ModelCatalogOption> {
        val items = value as? JsonArray ?: return emptyList()
        return items.mapNotNull { item ->
            val dict = item as? JsonObject ?: return@mapNotNull null
            val id = dict.stringValue("id") ?: return@mapNotNull null
            ModelCatalogOption(
                id = id,
                displayName = dict.stringValue("name") ?: dict.stringValue("label") ?: id,
                providerId = dict.stringValue("provider_id") ?: providerId,
            )
        }
    }

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null }
}

data class ModelCatalogGroup(
    val id: String,
    val name: String,
    val providerId: String?,
    val models: List<ModelCatalogOption>,
)

data class ModelCatalogOption(
    val id: String,
    val displayName: String,
    val providerId: String?,
)

/** `GET /api/commands`. */
@Serializable
data class CommandsResponse(val commands: List<AgentCommand>? = null)

@Serializable
data class AgentCommand(
    val name: String? = null,
    val description: String? = null,
    val category: String? = null,
    val aliases: List<String>? = null,
    @SerialName("args_hint") val argsHint: String? = null,
    @SerialName("cli_only") val cliOnly: Boolean? = null,
    @SerialName("gateway_only") val gatewayOnly: Boolean? = null,
)

/** `GET /api/profiles` / `POST /api/profile/switch`. */
@Serializable
data class ProfilesResponse(
    val profiles: List<ProfileSummary>? = null,
    val active: String? = null,
)

@Serializable
data class ProfileSwitchResponse(
    val profiles: List<ProfileSummary>? = null,
    val active: String? = null,
    @SerialName("default_model") val defaultModel: String? = null,
    @SerialName("default_workspace") val defaultWorkspace: String? = null,
    val error: String? = null,
)

@Serializable
data class ProfileSummary(
    val name: String? = null,
    val path: String? = null,
    @SerialName("is_default") val isDefault: Boolean? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
    val model: String? = null,
    val provider: String? = null,
) {
    val displayName: String
        get() = when {
            name.isNullOrEmpty() -> "Profile"
            name == "default" -> "Default"
            else -> name
        }
}

/** `GET /api/workspaces`. */
@Serializable
data class WorkspacesResponse(
    val workspaces: List<WorkspaceRoot>? = null,
    val last: String? = null,
)

@Serializable
data class WorkspaceRoot(
    val path: String? = null,
    val name: String? = null,
)

/** `GET`/`POST /api/default-model`. */
@Serializable
data class DefaultModelResponse(
    val ok: Boolean? = null,
    val model: String? = null,
)

/** `GET /api/settings`. */
@Serializable
data class SettingsResponse(
    @SerialName("bot_name") val botName: String? = null,
    @SerialName("webui_version") val webuiVersion: String? = null,
    val version: String? = null,
)

/** `POST /api/upload` (multipart). */
@Serializable
data class UploadResponse(
    val filename: String? = null,
    val path: String? = null,
    val size: Int? = null,
    val mime: String? = null,
    @SerialName("is_image") val isImage: Boolean? = null,
    val error: String? = null,
)
