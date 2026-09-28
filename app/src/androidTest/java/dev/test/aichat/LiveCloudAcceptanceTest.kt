package dev.test.aichat

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in live requests to the deployed cloud; no fake responses, quota resets, or purchases. */
class LiveCloudAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as ChatApplication
    private fun last() = runBlocking {
        val id = app.getSharedPreferences("ui", 0).getString("chat_id", null) ?: return@runBlocking null
        app.database.chats().messages(id).lastOrNull()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(300)
        val file = File(app.filesDir, "screenshots/$name.png").apply { parentFile!!.mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun realAnswersExhaustQuotaAndAutomaticallyShowPremium() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCloud") == "true")
        if (compose.onAllNodesWithText("Начать").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Начать").performClick()
        compose.waitUntil(30000) { app.repository.account.value.loaded }
        val count = app.repository.account.value.remaining
        assertTrue("A fresh cloud allowance is required; this test never resets it", count in 1..3)
        val prompts = listOf(
            "Объясни в трёх коротких пунктах, что такое SSE. Ответь по-русски, не больше 70 слов.",
            "Назови два способа хранить данные на Android. Ответь по-русски, не больше 30 слов.",
            "Пожелай хорошего дня одной короткой фразой по-русски."
        )
        repeat(count) { index ->
            if (compose.onAllNodesWithContentDescription("Новый чат").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Новый чат").performClick()
            compose.onNodeWithContentDescription("Сообщение").performTextInput(prompts[index])
            compose.onNodeWithContentDescription("Отправить").performClick()
            compose.waitUntil(190000) { last()?.let { it.role == "assistant" && it.status in listOf("complete", "error", "failed") } == true }
            var retries = 0
            while (last()?.status != "complete" && retries < 2) {
                // A failed provider result must return the reserved message before a manual retry.
                if (last()?.status == "failed") assertEquals(count - index, app.repository.account.value.remaining)
                val previousId = last()?.id
                compose.onNodeWithText("Повторить").performScrollTo().performClick()
                compose.waitUntil(190000) { last()?.let { it.id != previousId && it.status in listOf("complete", "error", "failed") } == true }
                retries++
            }
            assertEquals("Live provider failed: ${last()?.error}", "complete", last()?.status)
            assertTrue(last()!!.content.isNotBlank())
            compose.waitUntil(5000) { app.repository.account.value.remaining == count - index - 1 }
            if (index < count - 1) capture("cloud-answer-${index + 1}")
        }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Безлимитный доступ").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("PRO").assertIsDisplayed()
        compose.onNodeWithText("Восстановить покупки").assertIsDisplayed()
        capture("cloud-auto-paywall")
        compose.onNodeWithContentDescription("Закрыть пейвол").performClick()
        capture("cloud-last-answer")
    }
}
