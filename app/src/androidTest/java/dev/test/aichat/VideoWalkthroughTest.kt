package dev.test.aichat

import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.test.aichat.ui.ChatViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in real-time recording driver. No Compose test clock, mocked AI, or quota reset. */
class VideoWalkthroughTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: MainActivity
    private lateinit var vm: ChatViewModel
    private val app get() = activity.application as ChatApplication
    private fun pause(ms: Long = 1500) = Thread.sleep(ms)
    private fun find(label: String, description: Boolean = false): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if ((if (description) node.contentDescription else node.text)?.toString() == label && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return visit(root)
    }
    private fun waitFor(timeout: Long = 15000, predicate: () -> Boolean) {
        val until = System.currentTimeMillis() + timeout
        while (!predicate()) { check(System.currentTimeMillis() < until) { "Recording step timed out" }; pause(100) }
    }
    private fun click(label: String, description: Boolean = false) {
        waitFor { find(label, description) != null }
        var node = find(label, description)!!
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Cannot click $label" }
        pause(500)
    }
    private fun enter(prompt: String) {
        click("Сообщение", true)
        pause(600)
        check(find("Сообщение", true)!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt) }))
        pause(1300)
        click("Отправить", true)
        waitFor(190000) { !vm.ui.value.sending }
        val last = runBlocking { app.database.chats().messages(vm.ui.value.chatId!!).last() }
        assertEquals("Real provider did not finish: ${last.error}", "complete", last.status)
        waitFor(120000) { vm.ui.value.presentingId == null }
        pause(2300)
    }
    @Test fun recordThreeRealGenerationsHistoryAndPremium() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("record") == "true")
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.runOnMainSync { vm = ViewModelProvider(activity)[ChatViewModel::class.java] }
        pause(2000)
        if (find("Начать") != null) click("Начать")
        waitFor(30000) { app.repository.account.value.loaded }
        assertEquals("Use a clean test installation; never reset quota", 3, app.repository.account.value.remaining)
        pause(1800)
        val first = "Объясни SSE в 3 коротких пунктах, до 45 слов."
        enter(first)
        click("Новый чат", true)
        enter("Напиши вежливое письмо: перенести встречу на завтра. До 35 слов.")
        click("Список чатов", true)
        pause(2500)
        click(first)
        pause(2000)
        click("Новый чат", true)
        enter("Предложи план дня из 3 пунктов, до 30 слов.")
        waitFor { find("Безлимитный доступ") != null }
        pause(3500)
        val root = instrumentation.uiAutomation.rootInActiveWindow
        fun scroll(node: AccessibilityNodeInfo): Boolean {
            if (node.isScrollable && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            for (i in 0 until node.childCount) if (node.getChild(i)?.let(::scroll) == true) return true
            return false
        }
        scroll(root)
        pause(3500)
        assertNotNull(find("Восстановить покупки"))
    }
}
