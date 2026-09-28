package dev.test.aichat.network

import java.io.IOException
import kotlinx.coroutines.delay

internal fun retryDelay(error: IOException, attempt: Int): Long? {
    if (attempt !in 0..1) return null
    if (error is ApiException && error.code !in listOf(408, 429, 500, 502, 503, 504)) return null
    val requested = (error as? ApiException)?.retryAfterMillis ?: 0
    if (requested > 5000) return null
    return maxOf(if (attempt == 0) 1000L else 3000L, requested)
}

internal suspend fun <T> withNetworkRetries(block: suspend () -> T): T {
    var attempt = 0
    while (true) {
        try { return block() }
        catch (e: IOException) { delay(retryDelay(e, attempt++) ?: throw e) }
    }
}
