package com.codeflow.bluechat

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object CodeFlowLogger : DefaultLifecycleObserver {

    private const val TAG = "CodeFlowLogger"
    private const val BATCH_SIZE = 1000

    private val applicationId: String = BuildConfig.APPLICATION_ID
    private val apiUrl: String = "${BuildConfig.LOG_API_URL}/$applicationId"

    private val buffer = CopyOnWriteArrayList<JSONObject>()
    private val executor = Executors.newSingleThreadExecutor()
    private val isFlushing = AtomicBoolean(false)
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun init() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        info(TAG, "CodeFlowLogger initialized")
    }

    // region Public API

    fun debug(tag: String, message: String, metadata: Map<String, Any>? = null) {
        enqueue("DEBUG", tag, message, metadata)
    }

    fun info(tag: String, message: String, metadata: Map<String, Any>? = null) {
        enqueue("INFO", tag, message, metadata)
    }

    fun warning(tag: String, message: String, metadata: Map<String, Any>? = null) {
        enqueue("WARN", tag, message, metadata)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null, metadata: Map<String, Any>? = null) {
        val meta = buildThrowableMetadata(throwable, metadata)
        enqueue("ERROR", tag, message, meta)
        flush()
    }

    fun fatal(tag: String, message: String, throwable: Throwable? = null, metadata: Map<String, Any>? = null) {
        val meta = buildThrowableMetadata(throwable, metadata)
        enqueue("ERROR", tag, "[FATAL] $message", meta)
        flushSync()
    }

    // endregion

    // region Lifecycle — flush at every opportunity

    override fun onPause(owner: LifecycleOwner) {
        flush()
    }

    override fun onStop(owner: LifecycleOwner) {
        flushSync()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        flushSync()
    }

    override fun onStart(owner: LifecycleOwner) {
        flush()
    }

    // endregion

    // region Internals

    private fun enqueue(level: String, tag: String, message: String, metadata: Map<String, Any>? = null) {
        val entry = JSONObject().apply {
            put("level", level)
            put("message", message)
            put("source", tag)
            put("created_at", isoFormat.format(Date()))
            put("metadata", JSONObject(metadata.orEmpty().toMutableMap<String, Any?>().apply {
                put("application_id", applicationId)
            }))
        }
        buffer.add(entry)

        if (BuildConfig.DEBUG) {
            when (level) {
                "DEBUG" -> Log.d(tag, message)
                "INFO" -> Log.i(tag, message)
                "WARN" -> Log.w(tag, message)
                "ERROR" -> Log.e(tag, message)
            }
        }

        if (buffer.size >= BATCH_SIZE) {
            flush()
        }
    }

    fun flush() {
        if (buffer.isEmpty()) return
        executor.execute { drainAndUpload() }
    }

    private fun flushSync() {
        if (buffer.isEmpty()) return
        drainAndUpload()
    }

    private fun drainAndUpload() {
        if (!isFlushing.compareAndSet(false, true)) return
        try {
            // Snapshot and clear — CopyOnWriteArrayList iterators don't support remove()
            val snapshot = buffer.toList()
            buffer.clear()
            for (i in snapshot.indices step BATCH_SIZE) {
                val batch = snapshot.subList(i, minOf(i + BATCH_SIZE, snapshot.size))
                uploadBatch(batch)
            }
        } finally {
            isFlushing.set(false)
        }
    }

    private fun uploadBatch(batch: List<JSONObject>) {
        try {
            val payload = JSONObject().apply {
                put("logs", JSONArray(batch))
            }
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
            }
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                Log.w(TAG, "Log upload failed with status $responseCode")
                buffer.addAll(0, batch)
            }
            connection.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "Log upload failed: ${e.message}")
            buffer.addAll(0, batch)
        }
    }

    private fun buildThrowableMetadata(
        throwable: Throwable?,
        existing: Map<String, Any>?
    ): Map<String, Any>? {
        if (throwable == null) return existing
        val throwableData = mapOf<String, Any>(
            "exception_class" to throwable.javaClass.name,
            "exception_message" to (throwable.message ?: ""),
            "stacktrace" to Log.getStackTraceString(throwable)
        )
        return if (existing != null) existing + throwableData else throwableData
    }

    // endregion
}
