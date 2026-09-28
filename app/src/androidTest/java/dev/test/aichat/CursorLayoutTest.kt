package dev.test.aichat

import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import dev.test.aichat.ui.StreamingMarkdownView
import org.junit.Assert.*
import org.junit.Test

class CursorLayoutTest {
    @Test fun blinkingCursorDoesNotChangeWrappedTextOrRequestLayout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = StreamingMarkdownView(instrumentation.targetContext)
            view.text = "Длинный ответ с переносами строк. ".repeat(12)
            view.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            val originalText = view.text
            val originalLayout = view.layout
            assertTrue(view.lineCount > 1)
            assertFalse(view.isLayoutRequested)
            repeat(10) {
                view.showCursor(it % 2 == 0)
                assertSame(originalText, view.text)
                assertSame(originalLayout, view.layout)
                assertFalse("Cursor must invalidate paint only", view.isLayoutRequested)
            }
        }
    }
}
