package com.codeflow.bluechat.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.codeflow.bluechat.CodeFlowLogger
import com.codeflow.bluechat.ble.BleAdvertiser
import com.codeflow.bluechat.ble.BleScanner
import com.codeflow.bluechat.crypto.MessageEncryption
import com.codeflow.bluechat.model.BleStatus
import com.codeflow.bluechat.model.ChatMessage
import com.codeflow.bluechat.model.ReceivingMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

/**
 * ViewModel for the chat screen.
 * Manages BLE advertising/scanning and message state.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "ChatViewModel"

    // UI State
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _bleStatus = MutableStateFlow(BleStatus.STOPPED)
    val bleStatus: StateFlow<BleStatus> = _bleStatus.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _passphrase = MutableStateFlow("BlueChat2026")
    val passphrase: StateFlow<String> = _passphrase.asStateFlow()

    private val _receivingMessages = MutableStateFlow<Map<Int, ReceivingMessage>>(emptyMap())
    val receivingMessages: StateFlow<Map<Int, ReceivingMessage>> = _receivingMessages.asStateFlow()

    // BLE components
    private var secretKey: SecretKeySpec = MessageEncryption.deriveKey(_passphrase.value)
    private var bleAdvertiser: BleAdvertiser? = null
    private var bleScanner: BleScanner? = null

    init {
        CodeFlowLogger.info(TAG, "ChatViewModel initialized")
        initializeBleComponents(application)
    }

    private fun initializeBleComponents(context: Context) {
        bleAdvertiser = BleAdvertiser(context, secretKey)
        bleScanner = BleScanner(
            context = context,
            secretKey = secretKey,
            onMessageReceived = { message, messageId ->
                handleReceivedMessage(message, messageId)
            },
            onChunkProgress = { messageId, received, total ->
                handleChunkProgress(messageId, received, total)
            }
        )

        // Check BLE support
        if (bleAdvertiser?.isSupported() != true) {
            _errorMessage.value = "BLE advertising not supported on this device"
            CodeFlowLogger.error(TAG, "BLE advertising not supported", null)
        }
    }

    /**
     * Sends a new message.
     */
    fun sendMessage(content: String) {
        if (content.isBlank()) {
            CodeFlowLogger.debug(TAG, "Attempted to send empty message")
            return
        }

        CodeFlowLogger.info(TAG, "Sending message", mapOf(
            "message_length" to content.length
        ))

        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            content = content,
            timestamp = System.currentTimeMillis(),
            isOutgoing = true
        )

        // Add to UI immediately
        _messages.value = _messages.value + message

        // Broadcast via BLE
        viewModelScope.launch {
            bleAdvertiser?.broadcastMessage(content) { success ->
                if (!success) {
                    _errorMessage.value = "Failed to send message via BLE"
                    CodeFlowLogger.error(TAG, "Failed to broadcast message", null)
                } else {
                    CodeFlowLogger.info(TAG, "Message sent successfully")
                }
            }
        }
    }

    /**
     * Resends an existing outgoing message by rebroadcasting it over BLE.
     */
    fun resendMessage(messageId: String) {
        val message = _messages.value.firstOrNull { it.id == messageId }
        if (message == null) {
            CodeFlowLogger.warning(TAG, "Resend requested for unknown message", mapOf(
                "message_id" to messageId
            ))
            return
        }
        if (!message.isOutgoing) {
            CodeFlowLogger.warning(TAG, "Resend rejected for incoming message", mapOf(
                "message_id" to messageId
            ))
            return
        }

        CodeFlowLogger.info(TAG, "Resending message", mapOf(
            "message_id" to messageId,
            "message_length" to message.content.length
        ))

        viewModelScope.launch {
            bleAdvertiser?.broadcastMessage(message.content) { success ->
                if (!success) {
                    _errorMessage.value = "Failed to resend message via BLE"
                    CodeFlowLogger.error(TAG, "Failed to rebroadcast message", null, mapOf(
                        "message_id" to messageId
                    ))
                } else {
                    CodeFlowLogger.info(TAG, "Message resent successfully", mapOf(
                        "message_id" to messageId
                    ))
                }
            }
        }
    }

    /**
     * Deletes a message from the local conversation (does not affect remote peers).
     */
    fun deleteMessage(messageId: String) {
        val existed = _messages.value.any { it.id == messageId }
        if (!existed) {
            CodeFlowLogger.warning(TAG, "Delete requested for unknown message", mapOf(
                "message_id" to messageId
            ))
            return
        }
        CodeFlowLogger.info(TAG, "Deleting message", mapOf(
            "message_id" to messageId
        ))
        _messages.value = _messages.value.filterNot { it.id == messageId }
    }

    /**
     * Handles progress updates for an incoming chunked message.
     */
    private fun handleChunkProgress(messageId: Int, received: Int, total: Int) {
        CodeFlowLogger.debug(TAG, "Chunk progress", mapOf(
            "message_id" to messageId,
            "received" to received,
            "total" to total
        ))
        _receivingMessages.value = _receivingMessages.value +
            (messageId to ReceivingMessage(
                messageId = messageId,
                receivedChunks = received,
                totalChunks = total
            ))
    }

    /**
     * Handles a received message (or failed reassembly) from BLE.
     */
    private fun handleReceivedMessage(content: String?, messageId: Int) {
        // Always clear the in-progress placeholder
        _receivingMessages.value = _receivingMessages.value - messageId

        if (content.isNullOrBlank()) {
            CodeFlowLogger.warning(TAG, "Message reassembly finished without content", mapOf(
                "message_id" to messageId
            ))
            return
        }

        CodeFlowLogger.info(TAG, "Message received", mapOf(
            "message_length" to content.length,
            "message_id" to messageId
        ))

        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            content = content,
            timestamp = System.currentTimeMillis(),
            isOutgoing = false
        )

        _messages.value = _messages.value + message
    }

    /**
     * Starts BLE scanning.
     */
    fun startScanning() {
        CodeFlowLogger.info(TAG, "Starting BLE scanning")
        bleScanner?.startScanning()
        updateBleStatus()
    }

    /**
     * Stops BLE scanning.
     */
    fun stopScanning() {
        CodeFlowLogger.info(TAG, "Stopping BLE scanning")
        bleScanner?.stopScanning()
        updateBleStatus()
    }

    /**
     * Updates the BLE status based on current state.
     */
    private fun updateBleStatus() {
        // For now, we just track scanning status
        // In a real app, you'd track both advertising and scanning
        _bleStatus.value = BleStatus.SCANNING
    }

    /**
     * Updates the encryption passphrase.
     */
    fun updatePassphrase(newPassphrase: String) {
        if (newPassphrase.isBlank()) {
            _errorMessage.value = "Passphrase cannot be empty"
            return
        }

        CodeFlowLogger.info(TAG, "Updating encryption passphrase")
        _passphrase.value = newPassphrase
        secretKey = MessageEncryption.deriveKey(newPassphrase)

        // Reinitialize BLE components with new key
        cleanup()
        initializeBleComponents(getApplication())

        // Restart scanning if it was active
        startScanning()
    }

    /**
     * Clears the error message.
     */
    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * Clears all messages.
     */
    fun clearMessages() {
        CodeFlowLogger.info(TAG, "Clearing all messages")
        _messages.value = emptyList()
        _receivingMessages.value = emptyMap()
    }

    /**
     * Cleans up resources.
     */
    private fun cleanup() {
        bleAdvertiser?.cleanup()
        bleScanner?.cleanup()
    }

    override fun onCleared() {
        super.onCleared()
        CodeFlowLogger.info(TAG, "ChatViewModel cleared, cleaning up resources")
        cleanup()
    }
}
