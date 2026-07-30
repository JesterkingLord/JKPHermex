package com.hermexapp.android.features.chat

import com.hermexapp.android.model.AgentCommand
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerConfigTest {

    private val commands = listOf(
        AgentCommand(name = "compact", description = "Compress the session"),
        AgentCommand(name = "/model", description = "Switch model"),
        AgentCommand(name = "cli-thing", cliOnly = true),
        AgentCommand(name = "gateway-thing", gatewayOnly = true),
    )

    @Test
    fun `slash suggestions filter by prefix and exclude cli-only commands`() {
        val config = ComposerConfig(commands = commands)

        val all = config.slashSuggestions("/")
        assertEquals(listOf("compact", "/model"), all.map { it.name })

        val filtered = config.slashSuggestions("/mo")
        assertEquals(listOf("/model"), filtered.map { it.name })
    }

    @Test
    fun `no suggestions once an argument is being typed or without a slash`() {
        val config = ComposerConfig(commands = commands)
        assertTrue(config.slashSuggestions("/compact focus").isEmpty())
        assertTrue(config.slashSuggestions("hello").isEmpty())
    }

    @Test
    fun `attachment wire payload matches the iOS toJSONValue keys`() {
        val attachment = PendingAttachment(
            name = "a.png",
            path = "uploads/a.png",
            mime = "image/png",
            size = 123,
            isImage = true,
        )

        val json = attachment.toJsonElement()

        assertEquals(JsonPrimitive("a.png"), json["name"])
        assertEquals(JsonPrimitive("uploads/a.png"), json["path"])
        assertEquals(JsonPrimitive("image/png"), json["mime"])
        assertEquals(JsonPrimitive(123), json["size"])
        assertEquals(JsonPrimitive(true), json["is_image"])
    }

    @Test
    fun `message text gains the attached-files marker like iOS chatMessageText`() {
        val attachments = listOf(
            PendingAttachment("a.png", "uploads/a.png", "image/png", null, true),
            PendingAttachment("notes.txt", "uploads/notes.txt", "text/plain", null, false),
        )

        assertEquals(
            "look at these\n\n[Attached files: uploads/a.png, uploads/notes.txt]",
            PendingAttachment.messageText("look at these", attachments),
        )
        assertEquals("plain", PendingAttachment.messageText("plain", emptyList()))
    }

    @Test
    fun `session model seeds selection before catalog default (7_3 parity)`() {
        val fromSession = resolveSessionModelSelection(
            sessionModel = "minimax-m2.5",
            sessionProvider = "minimax",
            selectedModelId = null,
            selectedProviderId = null,
            defaultModel = "other-default",
        )
        assertEquals("minimax-m2.5", fromSession.modelId)
        assertEquals("minimax", fromSession.providerId)
    }

    @Test
    fun `user model pick wins over session model (7_3 parity)`() {
        val userPick = resolveSessionModelSelection(
            sessionModel = "session-model",
            sessionProvider = "session-provider",
            selectedModelId = "user-picked",
            selectedProviderId = "user-provider",
            defaultModel = "default",
        )
        assertEquals("user-picked", userPick.modelId)
        assertEquals("user-provider", userPick.providerId)
    }

    @Test
    fun `catalog default used only when session and selection empty (7_3 parity)`() {
        val fallback = resolveSessionModelSelection(
            sessionModel = null,
            sessionProvider = null,
            selectedModelId = "  ",
            selectedProviderId = null,
            defaultModel = "catalog-default",
        )
        assertEquals("catalog-default", fallback.modelId)
        assertEquals(null, fallback.providerId)
    }
}
