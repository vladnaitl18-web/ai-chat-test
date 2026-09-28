package dev.test.aichat

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Visual review on actual local data; does not create responses, buy or reset anything. */
class DesignReviewTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as ChatApplication
    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(300) // Allow the emulator compositor to present the tested frame.
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(app.filesDir, "screenshots/$name.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun reviewAllScreensWithoutSendingOrPurchasing() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("review") == "true")
        if (compose.onAllNodesWithText("Начать").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Начать").performClick()
        compose.waitUntil(30000) { app.repository.account.value.loaded }
        capture("redesign-chat")
        if (compose.onAllNodesWithContentDescription("Новый чат").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithContentDescription("Новый чат").performClick()
        }
        compose.onNodeWithContentDescription("Новый чат").assertDoesNotExist()
        compose.onNodeWithContentDescription("Подсказки для сообщения").assertDoesNotExist()
        compose.onNodeWithContentDescription("Отправить").assertIsNotEnabled()
        capture("redesign-empty")
        compose.onNodeWithContentDescription("Список чатов").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Стамбул")
        compose.onNode(hasSetTextAction()).assertTextContains("Стамбул")
        compose.onNodeWithText("Ничего не найдено").assertDoesNotExist()
        capture("russian-search")
        compose.onNodeWithContentDescription("Очистить поиск").performClick()
        capture("redesign-history")
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.onNodeWithText("Восстановить покупки").assertExists()
        capture("redesign-settings")
        compose.onNodeWithText("Условия", useUnmergedTree = true).performScrollTo()
        capture("redesign-settings-bottom")
        compose.onNodeWithText("Открыть подписку").performScrollTo().performClick()
        compose.waitUntil(30000) { !app.billing.state.value.loading }
        capture("redesign-paywall")
        compose.onNodeWithText("PRO").assertIsDisplayed()
        compose.onNodeWithText("Восстановить покупки").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Продолжить").assertIsNotEnabled() // No configured Play products in this test installation.
        compose.onNodeWithContentDescription("Закрыть пейвол").performClick()
    }
}
