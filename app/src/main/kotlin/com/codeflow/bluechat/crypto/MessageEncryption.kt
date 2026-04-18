package com.codeflow.bluechat.crypto

import android.util.Base64
import com.codeflow.bluechat.CodeFlowLogger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles AES-256-CBC encryption and decryption for chat messages.
 * Uses a shared symmetric key derived from a passphrase.
 */
object MessageEncryption {

    private const val TAG = "MessageEncryption"
    private const val ALGORITHM = "AES/CBC/PKCS5Padding"
    private const val KEY_ALGORITHM = "AES"
    private const val IV_SIZE = 16

    /**
     * Derives a 256-bit AES key from a passphrase using SHA-256.
     */
    fun deriveKey(passphrase: String): SecretKeySpec {
        CodeFlowLogger.debug(TAG, "Deriving encryption key from passphrase")
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(passphrase.toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(keyBytes, KEY_ALGORITHM)
    }

    /**
     * Encrypts a message using AES-256-CBC with a random IV.
     * Returns Base64-encoded string in format: IV||CIPHERTEXT
     */
    fun encrypt(message: String, secretKey: SecretKeySpec): String? {
        return try {
            CodeFlowLogger.debug(TAG, "Encrypting message of length ${message.length}")

            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)

            val iv = cipher.iv
            val encrypted = cipher.doFinal(message.toByteArray(StandardCharsets.UTF_8))

            // Combine IV and encrypted data
            val combined = iv + encrypted
            val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)

            CodeFlowLogger.debug(TAG, "Message encrypted successfully", mapOf(
                "original_length" to message.length,
                "encrypted_length" to encoded.length
            ))
            encoded
        } catch (e: Exception) {
            CodeFlowLogger.error(TAG, "Failed to encrypt message", e)
            null
        }
    }

    /**
     * Decrypts a Base64-encoded encrypted message.
     * Expected format: IV||CIPHERTEXT
     */
    fun decrypt(encryptedBase64: String, secretKey: SecretKeySpec): String? {
        return try {
            CodeFlowLogger.debug(TAG, "Decrypting message of length ${encryptedBase64.length}")

            val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)

            // Extract IV and ciphertext
            val iv = combined.sliceArray(0 until IV_SIZE)
            val ciphertext = combined.sliceArray(IV_SIZE until combined.size)

            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))

            val decrypted = cipher.doFinal(ciphertext)
            val message = String(decrypted, StandardCharsets.UTF_8)

            CodeFlowLogger.debug(TAG, "Message decrypted successfully", mapOf(
                "encrypted_length" to encryptedBase64.length,
                "decrypted_length" to message.length
            ))
            message
        } catch (e: Exception) {
            CodeFlowLogger.error(TAG, "Failed to decrypt message", e)
            null
        }
    }

    /**
     * Generates a random passphrase for testing purposes.
     */
    fun generateRandomPassphrase(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..32)
            .map { chars.random() }
            .joinToString("")
    }
}
