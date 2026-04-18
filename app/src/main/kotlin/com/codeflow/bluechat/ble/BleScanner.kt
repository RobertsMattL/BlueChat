package com.codeflow.bluechat.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
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
 * Manages BLE scanning for receiving encrypted chat messages.
 * Listens for advertising packets, reassembles chunked messages, and decrypts them.
 */
class BleScanner(
    private val context: Context,
    private val secretKey: SecretKeySpec,
    private val onMessageReceived: (String) -> Unit
) {
    private val TAG = "BleScanner"

    // Service UUID for the chat application (must match advertiser)
    private val SERVICE_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner? = bluetoothAdapter?.bluetoothLeScanner

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var isScanning = false
    private var cleanupJob: Job? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { handleScanResult(it) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            CodeFlowLogger.error(TAG, "BLE scan failed", null, mapOf(
                "error_code" to errorCode,
                "error_message" to getErrorMessage(errorCode)
            ))
            isScanning = false
        }
    }

    /**
     * Starts BLE scanning.
     */
    fun startScanning() {
        if (bleScanner == null) {
            CodeFlowLogger.error(TAG, "BLE scanner not available", null)
            return
        }

        if (isScanning) {
            CodeFlowLogger.warning(TAG, "BLE scanning already in progress")
            return
        }

        try {
            // Create scan filter for our service UUID
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()

            // Create scan settings
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build()

            bleScanner.startScan(listOf(filter), settings, scanCallback)
            isScanning = true

            CodeFlowLogger.info(TAG, "BLE scanning started")

            // Start periodic cleanup of old chunks
            startCleanupJob()
        } catch (e: SecurityException) {
            CodeFlowLogger.error(TAG, "Missing Bluetooth permissions", e)
        } catch (e: Exception) {
            CodeFlowLogger.error(TAG, "Failed to start BLE scanning", e)
        }
    }

    /**
     * Stops BLE scanning.
     */
    fun stopScanning() {
        if (isScanning) {
            try {
                bleScanner?.stopScan(scanCallback)
                isScanning = false
                cleanupJob?.cancel()
                CodeFlowLogger.info(TAG, "BLE scanning stopped")
            } catch (e: Exception) {
                CodeFlowLogger.error(TAG, "Error stopping BLE scan", e)
            }
        }
    }

    /**
     * Handles a received scan result.
     */
    private fun handleScanResult(result: ScanResult) {
        try {
            val serviceData = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID))
            if (serviceData == null) {
                CodeFlowLogger.debug(TAG, "No service data in scan result")
                return
            }

            CodeFlowLogger.debug(TAG, "Received BLE advertisement", mapOf(
                "data_size" to serviceData.size,
                "rssi" to result.rssi
            ))

            // Parse the chunk
            val chunk = MessageChunker.parseChunk(serviceData)
            if (chunk == null) {
                CodeFlowLogger.warning(TAG, "Failed to parse chunk from service data")
                return
            }

            // Add chunk and check if message is complete
            val completeMessage = MessageChunker.addChunk(chunk)
            if (completeMessage != null) {
                processCompleteMessage(completeMessage)
            }
        } catch (e: Exception) {
            CodeFlowLogger.error(TAG, "Error handling scan result", e)
        }
    }

    /**
     * Processes a complete reassembled message.
     */
    private fun processCompleteMessage(messageBytes: ByteArray) {
        try {
            // Convert to string (this is the encrypted base64 data)
            val encryptedMessage = String(messageBytes, Charsets.UTF_8)

            CodeFlowLogger.info(TAG, "Complete message received, decrypting", mapOf(
                "encrypted_length" to encryptedMessage.length
            ))

            // Decrypt the message
            val decryptedMessage = com.codeflow.bluechat.crypto.MessageEncryption.decrypt(
                encryptedMessage,
                secretKey
            )

            if (decryptedMessage != null) {
                CodeFlowLogger.info(TAG, "Message decrypted successfully", mapOf(
                    "message_length" to decryptedMessage.length
                ))
                onMessageReceived(decryptedMessage)
            } else {
                CodeFlowLogger.error(TAG, "Failed to decrypt message", null)
            }
        } catch (e: Exception) {
            CodeFlowLogger.error(TAG, "Error processing complete message", e)
        }
    }

    /**
     * Starts a periodic job to clean up old incomplete chunks.
     */
    private fun startCleanupJob() {
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            while (isScanning) {
                delay(60_000) // Run every minute
                MessageChunker.cleanupOldChunks()
            }
        }
    }

    /**
     * Checks if the device supports BLE scanning.
     */
    fun isSupported(): Boolean {
        val supported = bluetoothAdapter?.isEnabled == true
        if (!supported) {
            CodeFlowLogger.warning(TAG, "Bluetooth not enabled")
        }
        return supported
    }

    /**
     * Cleans up resources.
     */
    fun cleanup() {
        stopScanning()
        cleanupJob?.cancel()
        CodeFlowLogger.info(TAG, "BLE scanner cleaned up")
    }

    private fun getErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "Scan already started"
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App registration failed"
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Internal error"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
            else -> "Unknown error: $errorCode"
        }
    }
}
