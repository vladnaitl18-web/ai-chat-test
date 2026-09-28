package dev.test.aichat

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.test.aichat.data.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in real-service acceptance test. No substituted AI responses. Requires the local backend. */
class LiveSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as ChatApplication
    private fun messages(): List<MessageEntity> = runBlocking {
        val id = app.getSharedPreferences("ui", 0).getString("chat_id", null) ?: return@runBlocking emptyList()
        app.database.chats().messages(id)
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(app.filesDir, "screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun actualChatHistoryAndPaywall() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        if (compose.onAllNodesWithText("Начать").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Начать").performClick()
        compose.waitUntil(30000) { app.repository.account.value.loaded }
        if (compose.onAllNodesWithContentDescription("Новый чат").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Новый чат").performClick()
        compose.onNodeWithContentDescription("Отправить").assertIsNotEnabled()
        screenshot("01-empty")
        compose.onNodeWithContentDescription("Сообщение").performTextInput("Составь план поездки в Стамбул на 3 дня. Ответь по-русски, с заголовками и списками. Без долгих рассуждений.")
        compose.onNodeWithContentDescription("Отправить").performClick()
        compose.waitUntil(150000) { messages().lastOrNull()?.let { it.content.length > 80 || it.status in listOf("error", "failed") } == true }
        assertTrue("Expected a real AI response: ${messages().lastOrNull()?.error}", messages().last().content.isNotBlank())
        screenshot("02-response")
        compose.waitUntil(150000) { messages().lastOrNull()?.status != "streaming" }
        assertEquals("complete", messages().last().status)
        compose.onNodeWithContentDescription("Список чатов").performClick()
        screenshot("03-history")
        compose.onNodeWithText("Поиск по чатам").performTextInput("Стамбул")
        compose.onAllNodesWithText("Составь план поездки", substring = true).onFirst().assertExists()
        compose.onNodeWithContentDescription("Закрыть список").performClick()
        // Activity recreation verifies UI reconnects to persisted Room history.
        val expected = messages().last().content
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10000) { messages().lastOrNull()?.content == expected }
        screenshot("04-restored")
        compose.onNodeWithContentDescription("Список чатов").performClick()
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.onNodeWithText("Открыть подписку").performClick()
        compose.waitUntil(30000) { !app.billing.state.value.loading }
        compose.onNodeWithText("Безлимитный доступ").assertExists()
        screenshot("05-paywall")
        // No purchase is launched by this test.
        compose.onNodeWithContentDescription("Закрыть пейвол").performClick()
    }
}
