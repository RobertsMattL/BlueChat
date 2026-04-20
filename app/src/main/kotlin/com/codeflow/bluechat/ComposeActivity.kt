package com.codeflow.bluechat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeflow.bluechat.ui.ChatScreen
import com.codeflow.bluechat.ui.SettingsDialog
import com.codeflow.bluechat.ui.theme.BlueChatTheme
import com.codeflow.bluechat.viewmodel.ChatViewModel

class ComposeActivity : ComponentActivity() {

    private val TAG = "ComposeActivity"
    private val viewModel: ChatViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            CodeFlowLogger.info(TAG, "All Bluetooth permissions granted")
            viewModel.startScanning()
        } else {
            CodeFlowLogger.warning(TAG, "Bluetooth permissions denied", mapOf(
                "denied_permissions" to permissions.filter { !it.value }.keys.joinToString()
            ))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CodeFlowLogger.info(TAG, "ComposeActivity created")

        // Request Bluetooth permissions
        requestBluetoothPermissions()

        setContent {
            BlueChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BlueChatApp(viewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        CodeFlowLogger.debug(TAG, "ComposeActivity resumed")
        // Restart scanning when activity resumes
        if (hasBluetoothPermissions()) {
            viewModel.startScanning()
        }
    }

    override fun onPause() {
        super.onPause()
        CodeFlowLogger.debug(TAG, "ComposeActivity paused")
        // Stop scanning when activity pauses to save battery
        viewModel.stopScanning()
    }

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            CodeFlowLogger.info(TAG, "Requesting Bluetooth permissions", mapOf(
                "permissions" to missingPermissions.joinToString()
            ))
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            CodeFlowLogger.info(TAG, "All Bluetooth permissions already granted")
            viewModel.startScanning()
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }

        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}

@Composable
fun BlueChatApp(viewModel: ChatViewModel) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val bleStatus by viewModel.bleStatus.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val passphrase by viewModel.passphrase.collectAsStateWithLifecycle()

    var showSettings by remember { mutableStateOf(false) }

    ChatScreen(
        messages = messages,
        bleStatus = bleStatus.name,
        errorMessage = errorMessage,
        onSendMessage = { viewModel.sendMessage(it) },
        onResendMessage = { viewModel.resendMessage(it) },
        onDeleteMessage = { viewModel.deleteMessage(it) },
        onClearError = { viewModel.clearError() },
        onSettingsClick = { showSettings = true }
    )

    if (showSettings) {
        SettingsDialog(
            currentPassphrase = passphrase,
            onDismiss = { showSettings = false },
            onSave = { newPassphrase ->
                viewModel.updatePassphrase(newPassphrase)
            }
        )
    }
}
