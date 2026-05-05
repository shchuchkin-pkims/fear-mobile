package com.fear.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity): Long

    /** Most recent `limit` messages for a given room, oldest-first for the UI. */
    @Query("""
        SELECT * FROM messages
        WHERE roomId = :roomId
        ORDER BY ts ASC
        LIMIT :limit
    """)
    suspend fun loadRecent(roomId: String, limit: Int = 500): List<MessageEntity>

    /** Stream the same window so the UI can react to inserts. */
    @Query("""
        SELECT * FROM messages
        WHERE roomId = :roomId
        ORDER BY ts ASC
        LIMIT :limit
    """)
    fun observeRecent(roomId: String, limit: Int = 500): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages WHERE roomId = :roomId")
    suspend fun clearRoom(roomId: String)

    @Query("DELETE FROM messages")
    suspend fun clearAll()

    /** Last-activity preview / unread counts for the chat list (Phase B). */
    @Query("""
        SELECT roomId, MAX(ts) AS lastTs
        FROM messages
        GROUP BY roomId
        ORDER BY lastTs DESC
    """)
    suspend fun chatSummaries(): List<ChatSummaryRow>

    /** Stream of room-id summaries so the unified sidebar updates live as
     *  new messages land. Same shape as chatSummaries() but observable. */
    @Query("""
        SELECT roomId, MAX(ts) AS lastTs
        FROM messages
        GROUP BY roomId
        ORDER BY lastTs DESC
    """)
    fun observeChatSummaries(): Flow<List<ChatSummaryRow>>

    /** FTS-free fallback search until §17 wires up FTS5. Case-insensitive LIKE. */
    @Query("""
        SELECT * FROM messages
        WHERE text LIKE '%' || :needle || '%'
        ORDER BY ts DESC
        LIMIT :limit
    """)
    suspend fun search(needle: String, limit: Int = 200): List<MessageEntity>
}

data class ChatSummaryRow(
    val roomId: String,
    val lastTs: Long,
)
