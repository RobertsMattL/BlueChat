package com.codeflow.bluechat.model

/**
 * Represents a chat message in the conversation.
 */
data class ChatMessage(
    val id: String,
    val content: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val deviceId: String? = null
)

/**
 * Represents the connection status of BLE.
 */
enum class BleStatus {
    STOPPED,
    SCANNING,
    ADVERTISING,
    BOTH
}

/**
 * Represents an incoming chunked message currently being reassembled.
 */
data class ReceivingMessage(
    val messageId: Int,
    val receivedChunks: Int,
    val totalChunks: Int,
    val startedAt: Long = System.currentTimeMillis()
)
