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

/** One explicit live retry on the latest failed chat. Never loops or purchases anything. */
class LiveRetryTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as ChatApplication
    private fun messages() = runBlocking {
        val id = app.getSharedPreferences("ui", 0).getString("chat_id", null)
        if (id == null) emptyList() else app.database.chats().messages(id)
    }
    @Test fun retryLatestFailedAnswerOnce() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("retryLive") == "true")
        compose.waitUntil(30000) { app.repository.account.value.loaded }
        val remaining = app.repository.account.value.remaining
        compose.onNodeWithContentDescription("Список чатов").performClick()
        compose.onAllNodesWithText("Составь план поездки", substring = true).onFirst().performClick()
        compose.waitUntil(5000) { messages().lastOrNull()?.status == "failed" }
        val oldId = messages().last().id
        compose.onNodeWithText("Повторить").performClick()
        compose.waitUntil(160000) { messages().lastOrNull()?.let { it.id != oldId && it.status != "streaming" } == true }
        val result = messages().last()
        compose.waitForIdle()
        android.os.SystemClock.sleep(400)
        val file = File(app.filesDir, "screenshots/redesign-live-retry.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertEquals("Only the successful retry consumes the refunded allowance", remaining - 1, app.repository.account.value.remaining)
        assertEquals("Provider error: ${result.error}", "complete", result.status)
        assertTrue(result.content.length > 80)
    }
}
