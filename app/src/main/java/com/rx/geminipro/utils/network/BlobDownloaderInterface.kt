package com.rx.geminipro.utils.network

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.MimeTypeMap
import android.widget.Toast
import com.rx.geminipro.R
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Bounded chunks, one user-initiated transfer, and acknowledgments provide backpressure. */
class BlobDownloaderInterface(context: Context) {
    private val context = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var token: String? = null
    private var transfer: DownloadTransfer? = null
    private var mimeType = "application/octet-stream"
    private var closed = false
    private val timeout = Runnable { cancel() }

    fun isBusy(): Boolean = token != null

    fun prepare(): String {
        cancel()
        return UUID.randomUUID().toString().also {
            token = it
            main.postDelayed(timeout, 60_000)
        }
    }

    fun cancel() {
        token = null
        main.removeCallbacks(timeout)
        if (!closed) worker.execute { discard() }
    }

    /** Called only by the origin-restricted WebMessageListener on the UI thread. */
    fun onMessage(data: String, reply: (String) -> Unit) {
        if (closed || data.length > 70_000) return
        val message = runCatching { JSONObject(data) }.getOrNull() ?: return
        val transferToken = message.optString("token")
        if (token == null || transferToken != token) return
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, 60_000)
        worker.execute {
            if (transferToken != token) return@execute
            try {
                when (message.getString("type")) {
                    "begin" -> {
                        check(transfer == null)
                        transfer = DownloadTransfer(context.cacheDir, message.getLong("size"))
                        mimeType = message.optString("mime", "application/octet-stream")
                            .substringBefore(';').takeIf { it.matches(Regex("[\\w.+-]+/[\\w.+-]+")) }
                            ?: "application/octet-stream"
                    }
                    "chunk" -> {
                        val encoded = message.getString("data")
                        require(encoded.length <= 65_536)
                        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                        checkNotNull(transfer).write(bytes)
                    }
                    "end" -> {
                        check(token == transferToken)
                        saveDownload(checkNotNull(transfer).finish(), mimeType)
                        discard()
                        if (token == transferToken) token = null
                        main.post {
                            if (token == null) main.removeCallbacks(timeout)
                            toast(R.string.download_saved)
                        }
                    }
                    "abort" -> error("Transfer aborted")
                    else -> error("Unknown download message")
                }
                main.post { runCatching { reply(JSONObject().put("token", transferToken).put("ok", true).toString()) } }
            } catch (_: Exception) {
                discard()
                val wasCurrent = token == transferToken
                if (wasCurrent) token = null
                main.post {
                    if (token == null) main.removeCallbacks(timeout)
                    if (wasCurrent) toast(R.string.download_failed)
                    runCatching { reply(JSONObject().put("token", transferToken).put("ok", false).toString()) }
                }
            }
        }
    }

    private fun saveDownload(source: File, mime: String) {
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        val filename = "GeminiPro_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$extension"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri: Uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            try {
                checkNotNull(resolver.openOutputStream(uri)).use { target ->
                    source.inputStream().use { it.copyTo(target) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            check(dir.exists() || dir.mkdirs())
            val destination = File(dir, filename)
            try {
                source.inputStream().use { input -> destination.outputStream().use { input.copyTo(it) } }
            } catch (e: Exception) {
                destination.delete()
                throw e
            }
        }
    }

    private fun discard() {
        transfer?.close()
        transfer = null
    }

    private fun toast(resource: Int) = Toast.makeText(context, resource, Toast.LENGTH_SHORT).show()

    fun close() {
        if (closed) return
        cancel()
        closed = true
        worker.shutdown()
    }

    companion object { const val MAX_DOWNLOAD_BYTES = DownloadTransfer.MAX_BYTES }
}
