package dev.test.aichat

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Uses the actual Android IME, not a simulated keyboard or fake conversation. */
class KeyboardTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as ChatApplication
    private fun imeVisible(): Boolean = compose.runOnIdle {
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(250)
        val file = File(app.filesDir, "screenshots/$name.png").apply { parentFile!!.mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private fun assertAboveKeyboard(node: SemanticsNodeInteraction) {
        node.assertIsDisplayed()
        val bottom = node.fetchSemanticsNode().boundsInWindow.bottom
        val available = compose.runOnIdle {
            Rect().also { compose.activity.window.decorView.getWindowVisibleDisplayFrame(it) }.bottom
        }
        assertTrue("Control bottom $bottom must be above keyboard top $available", bottom <= available + 1)
    }
    @Test fun russianMultilineInputAndSearchWithRealKeyboard() {
        if (compose.onAllNodesWithText("Начать").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Начать").performClick()
        compose.waitUntil(30000) { app.repository.account.value.loaded }
        compose.waitForIdle()
        if (compose.onAllNodesWithContentDescription("Закрыть пейвол").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithContentDescription("Закрыть пейвол").performClick()
        compose.onNodeWithContentDescription("Список чатов").performClick()
        compose.onNodeWithText("Новый чат").performClick()
        val input = compose.onNodeWithContentDescription("Сообщение")
        compose.onNodeWithContentDescription("Отправить").assertIsNotEnabled()
        input.performClick()
        compose.waitUntil(10000) { imeVisible() }
        input.performTextInput("Привет!\nВторая строка по-русски.\nТретья строка.\nЧетвёртая строка.\nПятая строка.")
        assertAboveKeyboard(input)
        assertAboveKeyboard(compose.onNodeWithContentDescription("Отправить"))
        capture("keyboard-chat")
        Espresso.pressBack()
        compose.waitUntil(5000) { !imeVisible() }
        input.assertTextContains("Вторая строка по-русски.", substring = true)
        input.performClick()
        compose.waitUntil(5000) { imeVisible() }
        compose.onNodeWithContentDescription("Список чатов").performClick()
        compose.waitUntil(5000) { !imeVisible() }
        val search = compose.onNode(hasSetTextAction())
        search.performClick()
        compose.waitUntil(5000) { imeVisible() }
        search.performTextInput("Несуществующий чат")
        search.assertTextContains("Несуществующий чат")
        assertAboveKeyboard(search)
        assertAboveKeyboard(compose.onNodeWithText("Новый чат"))
        capture("keyboard-search")
        search.performImeAction()
        compose.waitUntil(5000) { !imeVisible() }
        search.performClick()
        compose.waitUntil(5000) { imeVisible() }
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.waitUntil(5000) { !imeVisible() }
        compose.onNodeWithText("Восстановить покупки").assertExists()
    }
}
