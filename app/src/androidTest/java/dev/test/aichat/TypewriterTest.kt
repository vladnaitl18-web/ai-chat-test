package dev.test.aichat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.junit4.createComposeRule
import dev.test.aichat.ui.Markdown
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** A component fixture only; no simulated answers are available in the application. */
class TypewriterTest {
    @get:Rule val compose = createComposeRule()
    @Test fun returningToViewportDoesNotReplayPreviouslyReceivedText() {
        compose.mainClock.autoAdvance = false
        val visible = mutableStateOf(true)
        val streaming = mutableStateOf(true)
        var completions = 0
        compose.setContent {
            val holder = rememberSaveableStateHolder()
            if (visible.value) holder.SaveableStateProvider("reply") {
                Markdown("Полученный ответ. ".repeat(50), streaming.value, true) { completions++ }
            }
        }
        compose.mainClock.advanceTimeBy(160)
        assertEquals(0, completions)
        visible.value = false
        compose.mainClock.advanceTimeBy(64)
        streaming.value = false
        visible.value = true
        compose.mainClock.advanceTimeBy(160)
        assertEquals("A restored message must show received text without replay", 1, completions)
    }
    @Test fun largeChunksDoNotSkipTypingAndCompletionWaitsForVisibleText() {
        compose.mainClock.autoAdvance = false
        val text = mutableStateOf("Привет 🙂 мир")
        val streaming = mutableStateOf(true)
        val animate = mutableStateOf(true)
        var completions = 0
        compose.setContent { MaterialTheme { Markdown(text.value, streaming.value, animate.value) { completions++; animate.value = false } } }
        compose.mainClock.advanceTimeBy(160)
        assertEquals(0, completions)
        // A large SSE chunk arriving at once must retain the same rate.
        text.value += " Очень длинный фрагмент, который нельзя показать за один кадр."
        streaming.value = false
        compose.mainClock.advanceTimeBy(160)
        assertEquals("Must not finish a whole incoming chunk instantly", 0, completions)
        compose.mainClock.advanceTimeBy(10000)
        assertEquals(1, completions)
    }
}
