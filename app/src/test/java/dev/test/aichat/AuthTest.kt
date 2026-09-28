package dev.test.aichat

import dev.test.aichat.network.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class AuthTest {
    private class MemoryStore : SessionStore {
        override val deviceId = "test-device-12345678"
        var tokens = Tokens("expired", "refresh")
        override fun read() = tokens
        override fun write(tokens: Tokens) { this.tokens = tokens }
    }
    @Test fun parallelExpiredRequestsShareOneRefresh() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"access_token":"new","refresh_token":"rotated"}"""))
            val store = MemoryStore()
            val auth = Auth(Api(server.url("/").toString()), store)
            val results = (1..20).map { async(Dispatchers.Default) { auth.refresh("expired") } }.awaitAll()
            assertTrue(results.all { it == "new" })
            assertEquals(1, server.requestCount)
            assertEquals("rotated", store.tokens.refresh)
        }
    }
    @Test fun networkServerErrorDoesNotEraseSessionOrRetryForever() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"message":"unavailable"}"""))
            val store = MemoryStore(); val auth = Auth(Api(server.url("/").toString()), store)
            try { auth.refresh("expired"); fail("Expected error") } catch (e: ApiException) { assertEquals(503, e.code) }
            assertEquals(Tokens("expired", "refresh"), store.tokens)
            assertEquals(1, server.requestCount)
        }
    }
    @Test fun requestRefreshIsBoundedWhenNewTokenAlsoRejected() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"expired"}"""))
            server.enqueue(MockResponse().setBody("""{"access_token":"new","refresh_token":"rotated"}"""))
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"expired again"}"""))
            val auth = Auth(Api(server.url("/").toString()), MemoryStore())
            try { auth.request("account"); fail("Expected error") } catch (e: ApiException) { assertEquals(401, e.code) }
            assertEquals(3, server.requestCount)
        }
    }
}
