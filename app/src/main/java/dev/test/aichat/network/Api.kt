package dev.test.aichat.network

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

class ApiException(val code: Int, override val message: String, val retryAfterMillis: Long? = null) : IOException(message)
class Api(private val baseUrl: String, private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()) {
    private fun request(path: String, token: String?, body: JSONObject?): Request = Request.Builder()
        .url(baseUrl.trimEnd('/') + "/" + path.trimStart('/'))
        .apply {
            token?.let { header("Authorization", "Bearer $it") }
            if (body != null) post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        }.build()
    suspend fun json(path: String, token: String? = null, body: JSONObject? = null): JSONObject {
        // Refresh rotates credentials and cannot be safely replayed after losing its response.
        return if (path == "auth/refresh") jsonOnce(path, token, body)
        else withNetworkRetries { jsonOnce(path, token, body) }
    }
    private suspend fun jsonOnce(path: String, token: String?, body: JSONObject?): JSONObject {
        val call = client.newCall(request(path, token, body))
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val json = runCatching { JSONObject(it.body?.string().orEmpty().ifEmpty { "{}" }) }.getOrNull()
                            if (!it.isSuccessful) throw ApiException(it.code, json?.optString("message", "Ошибка сервера (${it.code})") ?: "Ошибка сервера (${it.code})", retryAfter(it))
                            json ?: throw IOException("Некорректный ответ сервера")
                        }
                        if (continuation.isActive) continuation.resumeWith(Result.success(result))
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            })
        }
    }
    fun events(generationId: String, after: Int, token: String): Flow<SseEvent> = callbackFlow {
        val call = client.newCall(request("generations/$generationId/events?after=$after", token, null))
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(e) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) {
                            val message = runCatching { JSONObject(it.body?.string() ?: "{}").optString("message", "Ошибка сервера") }.getOrDefault("Ошибка сервера (${it.code})")
                            throw ApiException(it.code, message, retryAfter(it))
                        }
                        val source = it.body?.source() ?: throw IOException("Пустой ответ сервера")
                        val parser = SseParser { event ->
                            if (trySendBlocking(event).isFailure) throw IOException("Поток закрыт")
                        }
                        while (!call.isCanceled()) parser.line(source.readUtf8Line() ?: break)
                    }
                    close()
                } catch (e: Exception) { close(e) }
            }
        })
        awaitClose { call.cancel() }
    }
    private fun retryAfter(response: Response): Long? {
        val value = response.header("Retry-After") ?: return null
        return value.toLongOrNull()?.times(1000)?.coerceAtLeast(0)
            ?: runCatching { (java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0) }.getOrDefault(Long.MAX_VALUE)
    }
}
