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

class ApiException(val code: Int, override val message: String) : IOException(message)
class Api(private val baseUrl: String) {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    private fun request(path: String, token: String?, body: JSONObject?): Request = Request.Builder()
        .url(baseUrl.trimEnd('/') + "/" + path.trimStart('/'))
        .apply {
            token?.let { header("Authorization", "Bearer $it") }
            if (body != null) post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        }.build()
    suspend fun json(path: String, token: String? = null, body: JSONObject? = null): JSONObject {
        val call = client.newCall(request(path, token, body))
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val json = JSONObject(it.body?.string().orEmpty().ifEmpty { "{}" })
                            if (!it.isSuccessful) throw ApiException(it.code, json.optString("message", "Ошибка сервера (${it.code})"))
                            json
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
                        if (!it.isSuccessful) throw ApiException(it.code, JSONObject(it.body?.string() ?: "{}").optString("message", "Ошибка сервера"))
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
}
