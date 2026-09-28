package dev.test.aichat

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.test.aichat.data.ChatEntity
import dev.test.aichat.data.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class DeleteChatTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun cancelAndConfirmDeletePersistInRoom() {
        val app = compose.activity.application as ChatApplication
        val id = UUID.randomUUID().toString()
        val title = "Проверка удаления ${id.take(5)}"
        val message = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        try {
            runBlocking {
                app.database.chats().saveChat(ChatEntity(id, title, now, now))
                app.database.chats().saveMessage(MessageEntity(message, id, "user", "Тестовая запись только для проверки удаления", now))
            }
            if (compose.onAllNodesWithText("Начать").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Начать").performClick()
            compose.waitUntil(30000) { app.repository.account.value.loaded }
            compose.waitForIdle()
            if (compose.onAllNodesWithContentDescription("Закрыть пейвол").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Закрыть пейвол").performClick()
            compose.onNodeWithContentDescription("Список чатов").performClick()
            compose.onNodeWithText(title).performClick()
            compose.onNodeWithContentDescription("Список чатов").performClick()
            compose.onNodeWithContentDescription("Удалить чат «$title»").performClick()
            compose.onNodeWithText("Отмена").performClick()
            assertNotNull(runBlocking { app.database.chats().chat(id) })
            compose.onNodeWithContentDescription("Удалить чат «$title»").performClick()
            compose.onNodeWithText("Удалить", substring = false).performClick()
            compose.waitUntil(5000) { runBlocking { app.database.chats().chat(id) == null } }
            assertNull(runBlocking { app.database.chats().message(message) })
            compose.onNodeWithText(title).assertDoesNotExist()
            compose.onNodeWithContentDescription("Закрыть список").performClick()
            compose.onNodeWithContentDescription("Новый чат").assertDoesNotExist()
            compose.activityRule.scenario.recreate()
            assertNull(runBlocking { app.database.chats().chat(id) })
        } finally { runBlocking { app.database.chats().deleteChat(id) } }
    }
}
