package dev.test.aichat.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Tokens(val access: String, val refresh: String)
interface SessionStore { val deviceId: String; fun read(): Tokens?; fun write(tokens: Tokens) }
class SecureSessionStore(context: Context) : SessionStore {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    override val deviceId: String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).commit() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("chat-session", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("chat-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun read(): Tokens? {
        val raw = prefs.getString("tokens", null) ?: return null
        return runCatching {
            val bytes = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            Tokens(json.getString("access"), json.getString("refresh"))
        }.getOrNull()
    }
    override fun write(tokens: Tokens) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = JSONObject().put("access", tokens.access).put("refresh", tokens.refresh).toString().toByteArray(Charsets.UTF_8)
        val encrypted = cipher.iv + cipher.doFinal(data)
        check(prefs.edit().putString("tokens", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "Не удалось сохранить сессию" }
    }
}
class Auth(private val api: Api, val store: SessionStore) {
    private val mutex = Mutex()
    @Volatile private var tokens: Tokens? = store.read()
    private fun accept(json: JSONObject): String {
        val next = Tokens(json.getString("access_token"), json.getString("refresh_token"))
        store.write(next); tokens = next; return next.access
    }
    suspend fun token(): String = mutex.withLock {
        tokens?.access ?: accept(api.json("auth/device", body = JSONObject().put("device_id", store.deviceId)))
    }
    suspend fun refresh(rejected: String): String = mutex.withLock {
        val current = tokens
        if (current != null && current.access != rejected) return@withLock current.access
        if (current == null) return@withLock accept(api.json("auth/device", body = JSONObject().put("device_id", store.deviceId)))
        try { accept(api.json("auth/refresh", body = JSONObject().put("refresh_token", current.refresh))) }
        catch (e: ApiException) {
            // Only a definitive invalid refresh session permits device re-authentication; network errors never clear tokens.
            if (e.code != 401) throw e
            accept(api.json("auth/device", body = JSONObject().put("device_id", store.deviceId)))
        }
    }
    suspend fun request(path: String, body: JSONObject? = null): JSONObject {
        val token = token()
        return try { api.json(path, token, body) }
        catch (e: ApiException) { if (e.code == 401) api.json(path, refresh(token), body) else throw e }
    }
}
