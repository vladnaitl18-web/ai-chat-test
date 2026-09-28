package dev.test.aichat

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.test.aichat.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomPersistenceTest {
    @Test fun partialTextCursorAndChatSurviveReopeningDatabase() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "persistence-test-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, ChatDatabase::class.java, name).build()
        try {
            val first = open()
            first.chats().createTurn(ChatEntity("chat", "Вопрос", 1, 1), MessageEntity("user", "chat", "user", "Вопрос", 1), MessageEntity("answer", "chat", "assistant", createdAt = 2, status = "streaming", generationId = "generation"))
            first.chats().append("answer", 1, "Часть ")
            first.chats().append("answer", 2, "ответа")
            first.chats().append("answer", 2, "ответа") // replay must be ignored
            first.close()
            val second = open()
            assertEquals("Вопрос", second.chats().chat("chat")?.title)
            assertEquals("Часть ответа", second.chats().message("answer")?.content)
            assertEquals(2, second.chats().message("answer")?.lastEventId)
            second.chats().recoverInterrupted()
            assertEquals("error", second.chats().message("answer")?.status)
            second.chats().deleteChat("chat")
            assertNull(second.chats().chat("chat"))
            assertNull(second.chats().message("answer"))
            assertTrue(second.chats().messages("chat").isEmpty())
            second.close()
        } finally { context.deleteDatabase(name) }
    }
}
