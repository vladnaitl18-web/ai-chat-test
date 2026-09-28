package dev.test.aichat.data

import dev.test.aichat.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class Account(val remaining: Int = 0, val subscribed: Boolean = false, val loaded: Boolean = false)
class ChatRepository(val dao: ChatDao, private val api: Api, val auth: Auth) {
    private val _account = MutableStateFlow(Account())
    val account = _account.asStateFlow()
    private fun accept(json: JSONObject) {
        _account.value = Account(json.getInt("remaining"), json.getBoolean("subscribed"), true)
    }
    suspend fun refreshAccount() { accept(auth.request("account")) }
    suspend fun verifyPurchase(token: String) { accept(auth.request("billing/verify", JSONObject().put("purchase_token", token))) }
    suspend fun generate(messageId: String) {
        var message = dao.message(messageId) ?: return
        dao.saveMessage(message.copy(status = "streaming", error = null))
        try {
            if (message.generationId == null) {
                val history = dao.messages(message.chatId).filter { it.createdAt < message.createdAt && it.content.isNotBlank() }
                val messages = JSONArray().apply { history.takeLast(80).forEach { put(JSONObject().put("role", it.role).put("content", it.content)) } }
                val result = if (message.retryOf != null) auth.request("generations/${message.retryOf}/retry", JSONObject().put("request_id", message.id))
                    else auth.request("generations", JSONObject().put("request_id", message.id).put("messages", messages))
                accept(result)
                message = (dao.message(messageId) ?: return).copy(generationId = result.getString("generation_id"))
                dao.saveMessage(message)
            }
            var refreshCount = 0
            var networkRetryCount = 0
            while (true) {
                message = dao.message(messageId) ?: return
                val token = auth.token()
                var terminal = false
                try {
                    api.events(message.generationId!!, message.lastEventId, token).collect { event ->
                        val data = JSONObject(event.data)
                        when (event.type) {
                            "delta" -> dao.append(messageId, event.id?.toIntOrNull() ?: throw IOException("Нет номера события"), data.getString("text"))
                            "done", "cancelled" -> {
                                if (data.has("remaining")) accept(data)
                                val saved = dao.message(messageId) ?: return@collect
                                dao.saveMessage(saved.copy(status = if (event.type == "done") "complete" else "stopped", error = null))
                                terminal = true
                            }
                            "auth_expired" -> throw ApiException(401, "Токен истёк")
                            "failed" -> {
                                if (data.has("remaining")) accept(data)
                                throw ApiException(520, data.optString("message", "Ошибка сервиса AI"))
                            }
                        }
                    }
                    if (!terminal) throw IOException("Соединение прервано")
                    return
                } catch (e: IOException) {
                    if (terminal) return
                    if (e is ApiException && e.code == 401) {
                        if (refreshCount >= 3) throw e
                        refreshCount++
                        auth.refresh(token)
                    } else {
                        val wait = retryDelay(e, networkRetryCount++) ?: throw e
                        dao.message(messageId)?.let { dao.saveMessage(it.copy(error = "Восстанавливаю соединение ($networkRetryCount/2)…")) }
                        delay(wait)
                        dao.message(messageId)?.let { dao.saveMessage(it.copy(error = null)) }
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            dao.message(messageId)?.let { dao.saveMessage(it.copy(status = if (e is ApiException && e.code == 520) "failed" else "error", error = friendlyError(e))) }
            throw e
        }
    }
    suspend fun prepareRetry(messageId: String): String {
        val old = dao.message(messageId) ?: return messageId
        if (old.status != "failed" || old.generationId == null) return messageId
        val id = java.util.UUID.randomUUID().toString()
        dao.saveMessage(old.copy(status = "superseded"))
        dao.saveMessage(MessageEntity(id, old.chatId, "assistant", createdAt = System.currentTimeMillis(), status = "streaming", retryOf = old.generationId))
        return id
    }
    suspend fun stop(messageId: String) {
        val message = dao.message(messageId) ?: return
        dao.saveMessage(message.copy(status = "stopped", error = null))
            message.generationId?.let {
                val result = auth.request("generations/$it/cancel", JSONObject())
                if (result.has("remaining")) accept(result)
            }
    }
}
fun friendlyError(e: Throwable): String = when (e) {
    is ApiException -> e.message
    is java.net.ConnectException, is java.net.UnknownHostException -> "Нет соединения с сервером. Проверьте сеть и адрес backend."
    is java.net.SocketTimeoutException -> "Сервер не ответил вовремя. Проверьте сеть и повторите."
    is IOException -> "Соединение прервано. Проверьте сеть и повторите."
    else -> "Не удалось выполнить запрос. Попробуйте ещё раз."
}
