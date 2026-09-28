package dev.test.aichat

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.test.aichat.ui.ChatViewModel
import dev.test.aichat.ui.Screen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in real-time capture. Invokes the same ViewModel actions as UI; real HTTP/Room, no Compose test clock or quota reset. */
class VideoWalkthroughTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    private lateinit var vm: ChatViewModel
    private val app get() = activity.application as ChatApplication
    private fun pause(ms: Long = 1500) = Thread.sleep(ms)
    private fun action(block: () -> Unit) { instrumentation.runOnMainSync(block) }
    private fun waitFor(timeout: Long = 30000, predicate: () -> Boolean) {
        val until = System.currentTimeMillis() + timeout
        while (!predicate()) { check(System.currentTimeMillis() < until) { "Recording step timed out: ${vm.ui.value.screen}" }; pause(100) }
    }
    private fun enter(prompt: String) {
        action { vm.send(prompt) }
        waitFor(190000) { !vm.ui.value.sending }
        val last = runBlocking { app.database.chats().messages(vm.ui.value.chatId!!).last() }
        assertEquals("Real provider did not finish: ${last.error}", "complete", last.status)
        waitFor(120000) { vm.ui.value.presentingId == null }
        pause(2600)
    }
    @Test fun recordThreeRealGenerationsHistoryAndPremium() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("record") == "true")
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        action { vm = ViewModelProvider(activity)[ChatViewModel::class.java] }
        pause(2000)
        action { vm.finishOnboarding() }
        waitFor { app.repository.account.value.loaded && !vm.ui.value.connecting }
        assertEquals("Requires three available real credits; never reset quota", 3, app.repository.account.value.remaining)
        action { vm.openChat(null) }
        pause(2500)
        enter("Объясни SSE в 3 коротких пунктах, до 45 слов.")
        val first = vm.ui.value.chatId!!
        action { vm.openChat(null) }; pause(1500)
        enter("Напиши вежливое письмо: перенести встречу на завтра. До 35 слов.")
        action { vm.navigate(Screen.HISTORY) }; pause(3000)
        action { vm.openChat(first) }; pause(2500)
        action { vm.openChat(null) }; pause(1500)
        enter("Предложи план дня из 3 пунктов, до 30 слов.")
        waitFor { vm.ui.value.screen == Screen.PAYWALL }
        pause(5500)
        assertEquals(0, app.repository.account.value.remaining)
    }
}
