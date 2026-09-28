package dev.test.aichat.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chats")
data class ChatEntity(@PrimaryKey val id: String, val title: String, val createdAt: Long, val updatedAt: Long)

@Entity(tableName = "messages", foreignKeys = [ForeignKey(entity = ChatEntity::class, parentColumns = ["id"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE)], indices = [Index("chatId")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val role: String,
    val content: String = "",
    val createdAt: Long,
    val status: String = "complete",
    val generationId: String? = null,
    val retryOf: String? = null,
    val lastEventId: Int = 0,
    val error: String? = null,
)
data class ChatSummary(@Embedded val chat: ChatEntity, val preview: String?)

@Dao
abstract class ChatDao {
    @Query("SELECT chats.*, (SELECT content FROM messages WHERE chatId = chats.id AND content != '' ORDER BY createdAt DESC LIMIT 1) AS preview FROM chats ORDER BY updatedAt DESC")
    abstract fun observeChats(): Flow<List<ChatSummary>>
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt, id")
    abstract fun observeMessages(chatId: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt, id")
    abstract suspend fun messages(chatId: String): List<MessageEntity>
    @Query("SELECT * FROM chats WHERE id = :id")
    abstract suspend fun chat(id: String): ChatEntity?
    @Query("DELETE FROM chats WHERE id = :id")
    abstract suspend fun deleteChat(id: String)
    @Query("SELECT * FROM messages WHERE id = :id")
    abstract suspend fun message(id: String): MessageEntity?
    @Upsert abstract suspend fun saveChat(chat: ChatEntity)
    @Upsert abstract suspend fun saveMessage(message: MessageEntity)
    @Query("UPDATE chats SET updatedAt = :time WHERE id = :chatId")
    abstract suspend fun touch(chatId: String, time: Long)
    @Query("UPDATE messages SET status = 'error', error = 'Ответ был прерван. Нажмите «Повторить», чтобы продолжить.' WHERE status = 'streaming'")
    abstract suspend fun recoverInterrupted()
    @Transaction
    open suspend fun append(id: String, eventId: Int, delta: String) {
        val message = message(id) ?: return
        if (eventId <= message.lastEventId || message.status != "streaming") return
        saveMessage(message.copy(content = message.content + delta, lastEventId = eventId))
        touch(message.chatId, System.currentTimeMillis())
    }
    @Transaction
    open suspend fun createTurn(chat: ChatEntity, user: MessageEntity, assistant: MessageEntity) {
        saveChat(chat); saveMessage(user); saveMessage(assistant)
    }
}
@Database(entities = [ChatEntity::class, MessageEntity::class], version = 1, exportSchema = true)
abstract class ChatDatabase : RoomDatabase() { abstract fun chats(): ChatDao }
