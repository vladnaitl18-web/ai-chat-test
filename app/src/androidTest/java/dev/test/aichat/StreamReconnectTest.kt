package dev.test.aichat

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.test.aichat.data.*
import dev.test.aichat.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class StreamReconnectTest {
    @Test fun temporaryDisconnectResumesFromRoomCursorWithoutDuplicateText() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ChatDatabase::class.java).build()
        try { MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("id: 1\nevent: delta\ndata: {\"text\":\"Привет \"}\n\n"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("id: 2\nevent: delta\ndata: {\"text\":\"мир\"}\n\nid: 3\nevent: done\ndata: {\"remaining\":2,\"subscribed\":false}\n\n"))
            val store = object : SessionStore {
                override val deviceId = "test-device"
                override fun read() = Tokens("access", "refresh")
                override fun write(tokens: Tokens) = Unit
            }
            val api = Api(server.url("/").toString(), okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).retryOnConnectionFailure(false).build())
            val repo = ChatRepository(db.chats(), api, Auth(api, store))
            db.chats().saveChat(ChatEntity("chat", "Fixture", 1, 1))
            db.chats().saveMessage(MessageEntity("answer", "chat", "assistant", createdAt = 1, status = "streaming", generationId = "generation"))
            repo.generate("answer")
            val saved = db.chats().message("answer")!!
            assertEquals("Привет мир", saved.content)
            assertEquals("complete", saved.status)
            assertEquals(2, repo.account.value.remaining)
            assertEquals(2, server.requestCount)
            assertEquals("/generations/generation/events?after=0", server.takeRequest().path)
            assertEquals("/generations/generation/events?after=1", server.takeRequest().path)
        } } finally { db.close() }
    }
}
