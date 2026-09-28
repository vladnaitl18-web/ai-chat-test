package dev.test.aichat

import dev.test.aichat.network.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RetryPolicyTest {
    @Test fun backoffIsBoundedAndDoesNotRetryBusinessErrors() {
        assertEquals(1000L, retryDelay(IOException(), 0))
        assertEquals(3000L, retryDelay(IOException(), 1))
        assertNull(retryDelay(IOException(), 2))
        for (code in listOf(400, 401, 402, 403, 404, 520)) assertNull(retryDelay(ApiException(code, "error"), 0))
        assertEquals(4000L, retryDelay(ApiException(429, "wait", 4000), 0))
        assertNull(retryDelay(ApiException(429, "wait", 60000), 0))
    }
    @Test fun idempotentGenerationRequestRecoversAfterTemporaryServerFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("temporary unavailable"))
            server.enqueue(MockResponse().setBody("""{"generation_id":"same-id","remaining":2,"subscribed":false}"""))
            val api = Api(server.url("/").toString())
            val result = api.json("generations", "access", org.json.JSONObject().put("request_id", "same-id"))
            assertEquals("same-id", result.getString("generation_id"))
            assertEquals(2, server.requestCount)
            assertEquals(server.takeRequest().body.readUtf8(), server.takeRequest().body.readUtf8())
        }
    }
}
