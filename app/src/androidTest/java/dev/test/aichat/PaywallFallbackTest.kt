package dev.test.aichat

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Requires a device without configured Play products; never launches or fakes a purchase. */
class PaywallFallbackTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun unavailableStoreShowsLabeledPreviewPricesWithNoPurchase() {
        val app = compose.activity.application as ChatApplication
        if (compose.onAllNodesWithText("Начать").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Начать").performClick()
        compose.waitUntil(30000) { app.repository.account.value.loaded }
        compose.waitForIdle()
        if (compose.onAllNodesWithContentDescription("Закрыть пейвол").fetchSemanticsNodes().isEmpty()) compose.onNodeWithText("Попробовать Pro").performClick()
        compose.waitUntil(30000) { app.billing.state.value.plans.isNotEmpty() && !app.billing.state.value.loading }
        assertTrue(app.billing.state.value.plans.all { it.isPreview })
        compose.onNodeWithText("ТЕСТОВЫЕ ЦЕНЫ").assertIsDisplayed()
        compose.onNodeWithText("499 ₽").assertIsDisplayed()
        compose.onNodeWithText("1 290 ₽").assertIsDisplayed()
        compose.onNodeWithText("Продолжить").assertIsNotEnabled()
        compose.onNodeWithText("Восстановить покупки").assertIsDisplayed().assertIsEnabled()
        val file = File(app.filesDir, "screenshots/cloud-preview-prices.png").apply { parentFile!!.mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertFalse(app.repository.account.value.subscribed)
    }
}
