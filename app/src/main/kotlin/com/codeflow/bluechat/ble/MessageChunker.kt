package com.codeflow.bluechat.ble

import com.codeflow.bluechat.CodeFlowLogger
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles chunking and reassembly of large messages for BLE advertising.
 * BLE advertising packets are limited to 31 bytes, so we need to split large messages.
 *
 * Format: [MSG_ID:4][CHUNK_IDX:1][TOTAL_CHUNKS:1][DATA:up to 25 bytes]
 */
object MessageChunker {

    private const val TAG = "MessageChunker"

    // BLE advertising limits:
    // - Total packet: 31 bytes max (legacy advertising)
    // - Service UUID overhead: ~16 bytes
    // - Header (msg ID + chunk idx + total): 6 bytes
    // - Available for data: 31 - 16 - 6 = 9 bytes (using 12 to be safe with extended advertising)
    private const val MAX_CHUNK_SIZE = 12 // bytes available for actual data
    private const val HEADER_SIZE = 6 // 4 bytes msg ID + 1 byte chunk index + 1 byte total

    private val receivedChunks = ConcurrentHashMap<Int, MutableMap<Int, ByteArray>>()
    private val chunkMetadata = ConcurrentHashMap<Int, ChunkMetadata>()

    // Tracks recently completed message IDs so late-arriving duplicate chunks
    // don't spawn a fresh "in progress" state for an already-delivered message.
    private const val COMPLETED_TTL_MS = 5 * 60 * 1000L
    private val completedMessages = ConcurrentHashMap<Int, Long>()

    data class ChunkMetadata(
        val totalChunks: Int,
        var receivedCount: Int = 0,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class ChunkedMessage(
        val messageId: Int,
        val chunkIndex: Int,
        val totalChunks: Int,
        val data: ByteArray
    )

    sealed class ChunkResult {
        data class InProgress(
            val messageId: Int,
            val receivedChunks: Int,
            val totalChunks: Int
        ) : ChunkResult()

        data class Complete(
            val messageId: Int,
            val totalChunks: Int,
            val data: ByteArray
        ) : ChunkResult()
    }

    /**
     * Splits a message into chunks suitable for BLE advertising.
     * Returns a list of byte arrays, each representing one chunk.
     */
    fun chunkMessage(message: ByteArray): List<ByteArray> {
        val messageId = message.contentHashCode()
        val totalChunks = ((message.size + MAX_CHUNK_SIZE - 1) / MAX_CHUNK_SIZE).coerceAtMost(255)

        CodeFlowLogger.debug(
            TAG, "Chunking message ${message.size} chunks: ${totalChunks}", mapOf(
                "message_id" to messageId,
                "message_size" to message.size,
                "total_chunks" to totalChunks
            )
        )

        val chunks = mutableListOf<ByteArray>()

        for (chunkIndex in 0 until totalChunks) {
            val start = chunkIndex * MAX_CHUNK_SIZE
            val end = minOf(start + MAX_CHUNK_SIZE, message.size)
            val dataChunk = message.sliceArray(start until end)

            val chunk = ByteBuffer.allocate(HEADER_SIZE + dataChunk.size).apply {
                putInt(messageId)
                put(chunkIndex.toByte())
                put(totalChunks.toByte())
                put(dataChunk)
            }.array()

            chunks.add(chunk)
        }

        CodeFlowLogger.info(
            TAG, "Message chunked successfully", mapOf(
                "message_id" to messageId,
                "chunks_created" to chunks.size
            )
        )

        return chunks
    }

    /**
     * Parses a chunk from raw bytes.
     */
    fun parseChunk(data: ByteArray): ChunkedMessage? {
        return try {
            if (data.size < HEADER_SIZE) {
                CodeFlowLogger.warning(TAG, "Chunk too small", mapOf("size" to data.size))
                return null
            }

            val buffer = ByteBuffer.wrap(data)
            val messageId = buffer.getInt()
            val chunkIndex = buffer.get().toInt() and 0xFF
            val totalChunks = buffer.get().toInt() and 0xFF
            val payload = ByteArray(data.size - HEADER_SIZE)
            buffer.get(payload)


            CodeFlowLogger.warning(
                TAG,
                "message ID: $messageId, chunk index: $chunkIndex, total chunks: $totalChunks, payload size: ${payload.size}"
            )

            ChunkedMessage(messageId, chunkIndex, totalChunks, payload)
        } catch (e: Exception) {
            CodeFlowLogger.error(TAG, "Failed to parse chunk", e)
            null
        }
    }

    /**
     * Adds a received chunk and attempts to reassemble the complete message.
     * Returns a [ChunkResult] describing progress, or a [ChunkResult.Complete] when
     * all chunks have arrived. Returns null if the chunk could not be processed.
     */
    fun addChunk(chunk: ChunkedMessage): ChunkResult? {
        val messageId = chunk.messageId

        // Ignore duplicate chunks that arrive after a message has been fully
        // reassembled — advertising repeats the same chunks for several seconds.
        if (completedMessages.containsKey(messageId)) {
            CodeFlowLogger.debug(
                TAG, "Ignoring chunk for completed message", mapOf(
                    "message_id" to messageId,
                    "chunk_index" to chunk.chunkIndex
                )
            )
            return null
        }

        CodeFlowLogger.debug(
            TAG, "Received chunk", mapOf(
                "message_id" to messageId,
                "chunk_index" to chunk.chunkIndex,
                "total_chunks" to chunk.totalChunks
            )
        )

        // Initialize storage for this message
        val chunks = receivedChunks.getOrPut(messageId) { ConcurrentHashMap() }
        val metadata = chunkMetadata.getOrPut(messageId) {
            ChunkMetadata(totalChunks = chunk.totalChunks)
        }

        // Store the chunk if we don't have it yet
        if (!chunks.containsKey(chunk.chunkIndex)) {
            chunks[chunk.chunkIndex] = chunk.data
            metadata.receivedCount++
        }

        // Check if we have all chunks
        if (metadata.receivedCount >= metadata.totalChunks) {
            CodeFlowLogger.info(
                TAG, "All chunks received, reassembling message", mapOf(
                    "message_id" to messageId,
                    "total_chunks" to metadata.totalChunks
                )
            )

            // Reassemble the message
            val totalSize = chunks.values.sumOf { it.size }
            val completeMessage = ByteArray(totalSize)
            var offset = 0

            for (i in 0 until metadata.totalChunks) {
                val chunkData = chunks[i]
                if (chunkData == null) {
                    CodeFlowLogger.error(
                        TAG, "Missing chunk during reassembly", null, mapOf(
                            "message_id" to messageId,
                            "missing_chunk" to i
                        )
                    )
                    return null
                }
                System.arraycopy(chunkData, 0, completeMessage, offset, chunkData.size)
                offset += chunkData.size
            }

            val totalChunks = metadata.totalChunks

            // Clean up and mark as completed so later duplicates are ignored
            receivedChunks.remove(messageId)
            chunkMetadata.remove(messageId)
            completedMessages[messageId] = System.currentTimeMillis()

            CodeFlowLogger.info(
                TAG, "Message reassembled successfully", mapOf(
                    "message_id" to messageId,
                    "total_size" to completeMessage.size
                )
            )

            return ChunkResult.Complete(
                messageId = messageId,
                totalChunks = totalChunks,
                data = completeMessage
            )
        }

        return ChunkResult.InProgress(
            messageId = messageId,
            receivedChunks = metadata.receivedCount,
            totalChunks = metadata.totalChunks
        )
    }

    /**
     * Cleans up old incomplete messages and expired completed-message
     * markers (anything older than 5 minutes).
     */
    fun cleanupOldChunks() {
        val now = System.currentTimeMillis()
        val timeout = 5 * 60 * 1000L // 5 minutes

        val toRemove = chunkMetadata.filter { (_, metadata) ->
            now - metadata.timestamp > timeout
        }.keys

        toRemove.forEach { messageId ->
            receivedChunks.remove(messageId)
            chunkMetadata.remove(messageId)
            CodeFlowLogger.debug(TAG, "Cleaned up expired chunks", mapOf("message_id" to messageId))
        }

        val expiredCompleted = completedMessages.filter { (_, completedAt) ->
            now - completedAt > COMPLETED_TTL_MS
        }.keys
        expiredCompleted.forEach { completedMessages.remove(it) }
    }
}
