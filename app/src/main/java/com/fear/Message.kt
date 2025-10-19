package com.fear

data class Message(
    val room: String,
    val sender: String,
    val content: String,
    val timestamp: Long,
    val type: Byte = Common.MSG_TYPE_TEXT
)

data class FileTransfer(
    val filename: String,
    val totalSize: Long,
    var received: Long = 0,
    val expectedCrc: Long = 0,
    var currentCrc: Long = 0xFFFFFFFFL
)

data class AudioCallRequest(
    val room: String,
    val fromUser: String,
    val toUser: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class AudioCallResponse(
    val room: String,
    val fromUser: String,
    val toUser: String,
    val accepted: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class AudioUdpInfo(
    val room: String,
    val user: String,
    val udpPort: Int,
    val noncePrefix: ByteArray,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AudioUdpInfo

        if (room != other.room) return false
        if (user != other.user) return false
        if (udpPort != other.udpPort) return false
        if (!noncePrefix.contentEquals(other.noncePrefix)) return false
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = room.hashCode()
        result = 31 * result + user.hashCode()
        result = 31 * result + udpPort
        result = 31 * result + noncePrefix.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

data class AudioCallState(
    val isInCall: Boolean = false,
    val isCallActive: Boolean = false,
    val remoteUser: String = "",
    val isInitiator: Boolean = false,
    val udpPort: Int = 0,
    val localNoncePrefix: ByteArray? = null,
    val remoteNoncePrefix: ByteArray? = null
)