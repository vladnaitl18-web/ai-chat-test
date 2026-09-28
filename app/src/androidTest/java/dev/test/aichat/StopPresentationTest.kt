package dev.test.aichat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.test.aichat.data.Account
import dev.test.aichat.data.MessageEntity
import dev.test.aichat.ui.ChatScreen
import dev.test.aichat.ui.UiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class StopPresentationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun stopRemainsAvailableAfterSseCompletesWhileTextIsStillTyping() {
        compose.mainClock.autoAdvance = false
        var stops = 0
        compose.setContent {
            MaterialTheme {
                ChatScreen(UiState(presentingId = "reply", connecting = false), "Чат",
                    listOf(MessageEntity("reply", "chat", "assistant", "Длинный ответ. ".repeat(30), createdAt = 1, status = "complete")),
                    Account(2, false, true), {}, {}, {}, {}, { stops++ }, {}, {}, {})
            }
        }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithContentDescription("Завершить печатание").assertExists().performClick()
        assertEquals(1, stops)
        compose.onNodeWithContentDescription("Отправить").assertDoesNotExist()
    }
}
