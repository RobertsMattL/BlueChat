package com.codeflow.bluechat.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import com.codeflow.bluechat.CodeFlowLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

/**
 * Manages BLE advertising for broadcasting encrypted chat messages.
 * Messages are chunked and broadcast sequentially through BLE advertising packets.
 */
class BleAdvertiser(
    private val context: Context,
    private val secretKey: SecretKeySpec
) {
    private val TAG = "BleAdvertiser"

    // Service UUID for the chat application
    private val SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var currentAdvertiseJob: Job? = null
    private var isAdvertising = false

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            CodeFlowLogger.info(TAG, "BLE advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            CodeFlowLogger.error(TAG, "BLE advertising failed to start", null, mapOf(
                "error_code" to errorCode,
                "error_message" to getErrorMessage(errorCode)
            ))
            isAdvertising = false
        }
    }

    /**
     * Broadcasts an encrypted message via BLE advertising.
     * The message is chunked and each chunk is advertised sequentially.
     */
    fun broadcastMessage(message: String, onComplete: (Boolean) -> Unit = {}) {
        if (bleAdvertiser == null) {
            CodeFlowLogger.error(TAG, "BLE advertiser not available", null)
            onComplete(false)
            return
        }

        CodeFlowLogger.info(TAG, "Broadcasting message via BLE advertising", mapOf(
            "message_length" to message.length
        ))

        currentAdvertiseJob?.cancel()
        currentAdvertiseJob = scope.launch {
            try {
                // Encrypt the message
                val encryptedMessage = com.codeflow.bluechat.crypto.MessageEncryption.encrypt(message, secretKey)
                if (encryptedMessage == null) {
                    CodeFlowLogger.error(TAG, "Failed to encrypt message", null)
                    onComplete(false)
                    return@launch
                }

                // Chunk the encrypted message
                val chunks = MessageChunker.chunkMessage(encryptedMessage.toByteArray())
                CodeFlowLogger.info(TAG, "Message chunked for advertising", mapOf(
                    "total_chunks" to chunks.size
                ))

                // Broadcast each chunk
                var success = true
                chunks.forEachIndexed { index, chunk ->
                    val chunkSuccess = advertiseChunk(chunk)
                    if (!chunkSuccess) {
                        success = false
                        CodeFlowLogger.error(TAG, "Failed to advertise chunk", null, mapOf(
                            "chunk_index" to index
                        ))
                    }
                    // Small delay between chunks
                    delay(100)
                }

                // Stop advertising after all chunks sent
                stopAdvertising()

                CodeFlowLogger.info(TAG, "Message broadcast complete", mapOf(
                    "success" to success,
                    "chunks_sent" to chunks.size
                ))

                onComplete(success)
            } catch (e: Exception) {
                CodeFlowLogger.error(TAG, "Error broadcasting message", e)
                stopAdvertising()
                onComplete(false)
            }
        }
    }

    /**
     * Advertises a single chunk.
     */
    private suspend fun advertiseChunk(chunk: ByteArray): Boolean {
        return try {
            // Create advertise settings
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .setTimeout(0)
                .build()

            // Create advertise data with the chunk
            val data = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .addServiceData(ParcelUuid(SERVICE_UUID), chunk)
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .build()

            // Start advertising
            bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
            isAdvertising = true

            // Wait for the chunk to be advertised
            delay(500)

            true
        } catch (e: Exception) {
            CodeFlowLogger.error(TAG, "Failed to advertise chunk", e)
            false
        }
    }

    /**
     * Stops BLE advertising.
     */
    fun stopAdvertising() {
        if (isAdvertising) {
            try {
                bleAdvertiser?.stopAdvertising(advertiseCallback)
                isAdvertising = false
                CodeFlowLogger.info(TAG, "BLE advertising stopped")
            } catch (e: Exception) {
                CodeFlowLogger.error(TAG, "Error stopping advertising", e)
            }
        }
    }

    /**
     * Checks if the device supports BLE advertising.
     */
    fun isSupported(): Boolean {
        val supported = bluetoothAdapter?.isMultipleAdvertisementSupported == true
        if (!supported) {
            CodeFlowLogger.warning(TAG, "BLE advertising not supported on this device")
        }
        return supported
    }

    /**
     * Cleans up resources.
     */
    fun cleanup() {
        currentAdvertiseJob?.cancel()
        stopAdvertising()
        CodeFlowLogger.info(TAG, "BLE advertiser cleaned up")
    }

    private fun getErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data too large"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Already started"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
            else -> "Unknown error: $errorCode"
        }
    }
}
