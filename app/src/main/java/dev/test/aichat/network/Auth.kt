package dev.test.aichat.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.provider.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Tokens(val access: String, val refresh: String)
interface SessionStore {
    val deviceId: String
    val needsDeviceBinding: Boolean get() = false
    fun deviceBound() {}
    fun read(): Tokens?
    fun write(tokens: Tokens)
}
class SecureSessionStore(context: Context) : SessionStore {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    // App-scoped hash: the raw Android ID is never sent to the server.
    override val deviceId: String = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        ?.takeIf { it.isNotBlank() }?.let { id ->
            "android-" + MessageDigest.getInstance("SHA-256").digest("${context.packageName}:$id".toByteArray()).joinToString("") { "%02x".format(it) }
        } ?: (prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).commit() })
    override val needsDeviceBinding get() = prefs.getString("bound_device_id", null) != deviceId
    override fun deviceBound() { check(prefs.edit().putString("bound_device_id", deviceId).commit()) }
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
    private suspend fun deviceLogin(): String {
        val body = JSONObject().put("device_id", store.deviceId)
        if (store.needsDeviceBinding) tokens?.refresh?.let { body.put("legacy_refresh_token", it) }
        val result = api.json("auth/device", body = body)
        if (store.needsDeviceBinding && !result.optBoolean("device_bound"))
            throw ApiException(503, "Сервер обновляется. Повторите вход немного позже.")
        val access = accept(result)
        if (result.optBoolean("device_bound")) store.deviceBound()
        return access
    }
    suspend fun token(): String = mutex.withLock {
        if (store.needsDeviceBinding) deviceLogin() else tokens?.access ?: deviceLogin()
    }
    suspend fun refresh(rejected: String): String = mutex.withLock {
        val current = tokens
        if (current != null && current.access != rejected) return@withLock current.access
        if (current == null) return@withLock deviceLogin()
        try { accept(api.json("auth/refresh", body = JSONObject().put("refresh_token", current.refresh))) }
        catch (e: ApiException) {
            // Only a definitive invalid refresh session permits device re-authentication; network errors never clear tokens.
            if (e.code != 401) throw e
            deviceLogin()
        }
    }
    suspend fun request(path: String, body: JSONObject? = null): JSONObject {
        val token = token()
        return try { api.json(path, token, body) }
        catch (e: ApiException) { if (e.code == 401) api.json(path, refresh(token), body) else throw e }
    }
}
