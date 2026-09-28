package dev.test.aichat

import android.app.Application
import androidx.room.Room
import dev.test.aichat.billing.PlayBilling
import dev.test.aichat.data.*
import dev.test.aichat.network.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ChatApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val database by lazy { Room.databaseBuilder(this, ChatDatabase::class.java, "chats.db").build() }
    private val api by lazy { Api(BuildConfig.API_URL) }
    private val auth by lazy { Auth(api, SecureSessionStore(this)) }
    val repository by lazy { ChatRepository(database.chats(), api, auth) }
    val billing by lazy { PlayBilling(this, repository, appScope) }
}
