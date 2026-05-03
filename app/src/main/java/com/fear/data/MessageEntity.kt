package com.fear.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One persisted text/system message in the local history database.
 *
 * `roomId` is the canonical room identifier shown to the user (matches the
 * `room` field on the wire today; will be replaced by HMAC-derived opaque
 * IDs in Phase D). Indexed for fast `WHERE roomId = ? ORDER BY ts` reads.
 *
 * `senderPkB64` is the URL-safe base64 of the sender's identity_pk when
 * available — the durable identifier across name changes. Empty for system
 * messages and for peers who haven't sent IDENTITY_ANNOUNCE yet.
 */
@Entity(
    tableName = "messages",
    indices = [Index("roomId", "ts")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roomId: String,
    val senderName: String,
    val senderPkB64: String = "",
    val text: String,
    val ts: Long,            // unix millis
    val fromSelf: Boolean,
    val isSystem: Boolean = false,
)
