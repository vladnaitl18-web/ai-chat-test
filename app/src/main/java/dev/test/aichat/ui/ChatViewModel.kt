package dev.test.aichat.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.test.aichat.ChatApplication
import dev.test.aichat.data.*
import dev.test.aichat.network.ApiException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

enum class Screen { CHAT, HISTORY, PAYWALL, SETTINGS }
data class UiState(val screen: Screen = Screen.CHAT, val chatId: String? = null, val sending: Boolean = false, val error: String? = null, val connecting: Boolean = true, val onboarded: Boolean = false, val presentingId: String? = null)
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ChatApplication
    private val repo = app.repository
    val billing = app.billing
    private val prefs = application.getSharedPreferences("ui", 0)
    private val _ui = MutableStateFlow(UiState(chatId = prefs.getString("chat_id", null), onboarded = prefs.getBoolean("onboarded", false)))
    val ui = _ui.asStateFlow()
    val account = repo.account
    val chats = repo.dao.observeChats().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val messages = _ui.map { it.chatId }.distinctUntilChanged().flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.dao.observeMessages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private var generationJob: Job? = null
    private var activeMessage: String? = null
    private var stopping = false
    init {
        viewModelScope.launch { repo.dao.recoverInterrupted(); connect() }
        viewModelScope.launch { billing.restore(silent = true) }
        viewModelScope.launch {
            combine(account, ui.map { it.sending || it.presentingId != null }.distinctUntilChanged()) { account, busy -> account to busy }
                .collect { (account, sending) ->
                    if (account.loaded && !sending) {
                        if (account.subscribed && _ui.value.screen == Screen.PAYWALL) navigate(Screen.CHAT)
                        else if (!account.subscribed && account.remaining <= 0 && _ui.value.screen == Screen.CHAT) navigate(Screen.PAYWALL)
                    }
                }
        }
    }
    fun connect() {
        viewModelScope.launch {
            _ui.update { it.copy(connecting = true, error = null) }
            try { withContext(Dispatchers.IO) { repo.refreshAccount() } }
            catch (e: Exception) { if (e is CancellationException) throw e; _ui.update { it.copy(error = friendlyError(e)) } }
            finally { _ui.update { it.copy(connecting = false) } }
        }
    }
    fun finishOnboarding() { prefs.edit().putBoolean("onboarded", true).apply(); _ui.update { it.copy(onboarded = true) } }
    fun navigate(screen: Screen) {
        _ui.update { it.copy(screen = screen, presentingId = if (screen == Screen.CHAT) it.presentingId else null) }
        if (screen == Screen.PAYWALL) viewModelScope.launch { billing.load() }
    }
    fun openChat(id: String?) {
        prefs.edit().putString("chat_id", id).apply()
        _ui.update { it.copy(chatId = id, screen = Screen.CHAT, error = null) }
    }
    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || _ui.value.sending || _ui.value.presentingId != null || _ui.value.connecting) return
        if (!account.value.loaded) { connect(); return }
        if (!account.value.subscribed && account.value.remaining <= 0) { navigate(Screen.PAYWALL); return }
        val chatId = _ui.value.chatId ?: UUID.randomUUID().toString()
        val id = UUID.randomUUID().toString()
        _ui.update { it.copy(sending = true, error = null, presentingId = id) }
        generationJob = viewModelScope.launch {
            activeMessage = id
            try {
                val now = System.currentTimeMillis()
                val existing = repo.dao.chat(chatId)
                val title = existing?.title ?: text.replace('\n', ' ').take(55)
                val chat = ChatEntity(chatId, title, existing?.createdAt ?: now, now)
                repo.dao.createTurn(chat, MessageEntity(UUID.randomUUID().toString(), chatId, "user", text, now), MessageEntity(id, chatId, "assistant", createdAt = now + 1, status = "streaming"))
                openChat(chatId)
                repo.generate(id)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (e is ApiException && e.code == 402) navigate(Screen.PAYWALL) }
            finally {
                val completed = currentCoroutineContext().isActive && activeMessage?.let { repo.dao.message(it)?.status == "complete" } == true
                activeMessage = null
                if (!stopping) _ui.update { it.copy(sending = false, presentingId = if (completed) it.presentingId else null) }
            }
        }
    }
    fun retry(id: String) {
        if (_ui.value.sending) return
        _ui.update { it.copy(sending = true) }
        generationJob = viewModelScope.launch {
            try {
                val retryId = repo.prepareRetry(id)
                activeMessage = retryId
                _ui.update { it.copy(presentingId = retryId) }
                repo.generate(retryId)
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (e is ApiException && e.code == 402) navigate(Screen.PAYWALL) }
            finally {
                val completed = currentCoroutineContext().isActive && activeMessage?.let { repo.dao.message(it)?.status == "complete" } == true
                activeMessage = null
                if (!stopping) _ui.update { it.copy(sending = false, presentingId = if (completed) it.presentingId else null) }
            }
        }
    }
    fun stop() {
        val id = activeMessage ?: run {
            // The response can finish over SSE before its presentation. Keep every received byte.
            _ui.update { it.copy(presentingId = null) }
            return
        }
        if (stopping) return
        stopping = true
        val job = generationJob
        viewModelScope.launch {
            job?.cancelAndJoin()
            try { withContext(Dispatchers.IO) { repo.stop(id) } }
            catch (e: Exception) { if (e is CancellationException) throw e; _ui.update { it.copy(error = "Получение остановлено. Сервер не подтвердил отмену: проверьте сеть.") } }
            finally { stopping = false; _ui.update { it.copy(sending = false, presentingId = null) } }
        }
    }
    fun responsePresented(id: String) {
        _ui.update { if (it.presentingId == id) it.copy(presentingId = null) else it }
    }
    fun restore() { viewModelScope.launch { billing.restore() } }
    fun deleteChat(id: String) {
        viewModelScope.launch {
            val current = activeMessage?.let { repo.dao.message(it) }
            if (current?.chatId == id) {
                stopping = true
                try {
                    generationJob?.cancelAndJoin()
                    try { withContext(Dispatchers.IO) { repo.stop(current.id) } }
                    catch (e: Exception) { if (e is CancellationException) throw e }
                } finally { stopping = false; _ui.update { it.copy(sending = false, presentingId = null) } }
            }
            // Also cancel a stream left running before a previous process was closed.
            repo.dao.messages(id).filter { it.status in listOf("streaming", "error") && it.generationId != null && it.id != current?.id }
                .forEach {
                    try { withContext(Dispatchers.IO) { repo.stop(it.id) } }
                    catch (e: Exception) { if (e is CancellationException) throw e }
                }
            repo.dao.deleteChat(id)
            if (_ui.value.chatId == id) {
                prefs.edit().remove("chat_id").apply()
                _ui.update { it.copy(chatId = null, sending = false, error = null) }
            }
        }
    }
}
