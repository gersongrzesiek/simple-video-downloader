package com.simplevideodownloader.download

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.simplevideodownloader.App
import com.simplevideodownloader.R
import com.simplevideodownloader.data.DownloadRecord
import com.simplevideodownloader.data.DownloadStore
import com.simplevideodownloader.ui.MainActivity
import com.simplevideodownloader.ui.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Descarga un archivo directo (mp4, webm, mp3...) o un vídeo HLS (m3u8) y lo guarda en Descargas.
 * Corre como servicio en primer plano con una notificación de progreso.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val fileName = inputData.getString(KEY_NAME) ?: "video.mp4"
    private val recordId = inputData.getString(KEY_RECORD)
    private val store = DownloadStore.get(applicationContext)
    private var bytesWritten = 0L
    private val notificationId = id.hashCode()
    private var lastProgressUpdate = 0L

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure(workDataOf(KEY_ERROR to "Falta la URL"))
        setForeground(foregroundInfo(0, indeterminate = true))
        val saver = MediaStoreSaver(applicationContext)
        var output: MediaStoreSaver.Output? = null
        return try {
            val result = withContext(Dispatchers.IO) {
                if (inputData.getBoolean(KEY_HLS, false)) {
                    downloadHls(url, saver) { output = it }
                } else {
                    downloadDirect(url, saver) { output = it }
                }
            }
            recordId?.let { id ->
                val duration = withContext(Dispatchers.IO) {
                    Thumbnails.create(applicationContext, result, store.thumbnailFile(id))
                    Thumbnails.durationMs(applicationContext, result)
                }
                store.update(id) {
                    it.copy(
                        status = DownloadRecord.Status.DONE, uri = result.toString(), sizeBytes = bytesWritten,
                        completedAt = System.currentTimeMillis(), error = null, durationMs = duration,
                    )
                }
            }
            notifyFinished(result)
            Result.success(workDataOf(KEY_URI to result.toString(), KEY_NAME to fileName))
        } catch (e: Exception) {
            output?.let { saver.abort(it) }
            if (isStopped) {
                markFailed(applicationContext.getString(R.string.status_cancelled))
                return Result.failure()
            }
            val retryable = e is IOException && runAttemptCount < 2
            if (retryable) {
                Result.retry()
            } else {
                val message = e.message ?: e.javaClass.simpleName
                markFailed(message)
                Result.failure(workDataOf(KEY_ERROR to message))
            }
        }
    }

    private fun markFailed(message: String) {
        recordId?.let { id -> store.update(id) { it.copy(status = DownloadRecord.Status.FAILED, error = message) } }
    }

    // --- Descarga directa ---

    private suspend fun downloadDirect(url: String, saver: MediaStoreSaver, onOutput: (MediaStoreSaver.Output) -> Unit): Uri {
        execute(url).use { response ->
            val body = response.body ?: throw IOException("Respuesta vacía")
            val mime = inputData.getString(KEY_MIME) ?: body.contentType()?.let { "${it.type}/${it.subtype}" } ?: "video/mp4"
            val total = body.contentLength()
            val output = saver.create(fileName, mime).also(onOutput)
            val buffer = ByteArray(64 * 1024)
            var done = 0L
            body.byteStream().use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.stream.write(buffer, 0, read)
                    done += read
                    bytesWritten = done
                    reportProgress(done, total)
                }
            }
            return saver.finish(output, mime)
        }
    }

    // --- HLS ---

    private suspend fun downloadHls(url: String, saver: MediaStoreSaver, onOutput: (MediaStoreSaver.Output) -> Unit): Uri {
        var playlistUrl = url
        var playlist = HlsParser.parse(fetchText(playlistUrl), playlistUrl)
        if (playlist is HlsPlaylist.Master) {
            // Si nos pasan la lista maestra, elegimos la mejor calidad.
            playlistUrl = playlist.variants.firstOrNull()?.url ?: throw IOException("La lista no tiene calidades")
            playlist = HlsParser.parse(fetchText(playlistUrl), playlistUrl)
        }
        val media = playlist as HlsPlaylist.Media
        if (media.isLive) throw IOException("Es una emisión en directo; solo se pueden descargar vídeos completos")
        if (media.isEncryptedUnsupported) throw IOException("Este vídeo usa un cifrado (DRM) que no se puede descargar")
        if (media.segments.isEmpty()) throw IOException("La lista no tiene segmentos")

        val isFmp4 = media.initSegment != null
        val mime = if (isFmp4) "video/mp4" else "video/mp2t"
        val name = if (isFmp4) fileName.substringBeforeLast('.') + ".mp4" else fileName
        val output = saver.create(name, mime).also(onOutput)
        val keys = HashMap<String, ByteArray>()

        media.initSegment?.let { init ->
            val bytes = fetchSegment(init, keys)
            output.stream.write(bytes)
            bytesWritten += bytes.size
        }

        // Bajamos varios segmentos a la vez pero los escribimos en orden.
        val total = media.segments.size
        var written = 0
        for (window in media.segments.chunked(PARALLEL_SEGMENTS)) {
            currentCoroutineContext().ensureActive()
            val parts = coroutineScope { window.map { seg -> async { fetchSegmentWithRetry(seg, keys) } }.map { it.await() } }
            for (part in parts) {
                output.stream.write(part)
                bytesWritten += part.size
                written++
            }
            reportProgress(written.toLong(), total.toLong(), unit = "segmentos")
        }
        return saver.finish(output, mime)
    }

    private suspend fun fetchSegmentWithRetry(segment: HlsSegment, keys: MutableMap<String, ByteArray>): ByteArray {
        var lastError: Exception? = null
        repeat(3) {
            try {
                return fetchSegment(segment, keys)
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("No se pudo descargar un segmento")
    }

    private fun fetchSegment(segment: HlsSegment, keys: MutableMap<String, ByteArray>): ByteArray {
        val range = segment.byteRange?.let { "bytes=${it.first}-${it.last}" }
        val data = execute(segment.url, range).use { it.body!!.bytes() }
        val key = segment.key ?: return data
        val keyUri = key.uri ?: throw IOException("Falta la clave del vídeo")
        val keyBytes = synchronized(keys) { keys[keyUri] } ?: execute(keyUri).use { it.body!!.bytes() }.also {
            synchronized(keys) { keys[keyUri] = it }
        }
        // Si la lista no trae IV, el estándar usa el número de secuencia del segmento.
        val iv = key.iv ?: ByteBuffer.allocate(16).putLong(8, segment.sequence).array()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    // --- Red ---

    private fun execute(url: String, range: String? = null): Response {
        val request = Request.Builder().url(url).apply {
            inputData.getString(KEY_REFERER)?.takeIf { it.startsWith("http") }?.let {
                header("Referer", it)
                originOf(it)?.let { origin -> header("Origin", origin) }
            }
            inputData.getString(KEY_USER_AGENT)?.let { header("User-Agent", it) }
            inputData.getString(KEY_COOKIES)?.let { header("Cookie", it) }
            range?.let { header("Range", it) }
        }.build()
        val response = App.http.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("El servidor respondió ${response.code}")
        }
        return response
    }

    private fun originOf(url: String): String? =
        url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" }

    private fun fetchText(url: String): String = execute(url).use { it.body!!.string() }

    // --- Progreso y notificaciones ---

    private suspend fun reportProgress(done: Long, total: Long, unit: String = "bytes") {
        val now = System.currentTimeMillis()
        if (now - lastProgressUpdate < 700 && done != total) return
        lastProgressUpdate = now
        val percent = if (total > 0) ((done * 100) / total).toInt() else -1
        setProgress(workDataOf(KEY_PROGRESS to percent, KEY_DONE to done, KEY_TOTAL to total, KEY_UNIT to unit))
        setForeground(foregroundInfo(percent.coerceAtLeast(0), indeterminate = percent < 0))
    }

    private fun foregroundInfo(percent: Int, indeterminate: Boolean): ForegroundInfo {
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, App.DOWNLOAD_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(fileName)
            .setContentText(applicationContext.getString(R.string.downloading))
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openDownloadsIntent())
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, applicationContext.getString(R.string.cancel), cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun notifyFinished(uri: Uri) {
        val ctx = applicationContext
        val open = Intent(ctx, PlayerActivity::class.java).setData(uri)
            .putExtra(PlayerActivity.EXTRA_TITLE, fileName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(ctx, notificationId, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(ctx, App.DOWNLOAD_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(fileName)
            .setContentText(ctx.getString(R.string.download_complete))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(notificationId + 1, notification) }
    }

    private fun openDownloadsIntent(): PendingIntent {
        val intent = Intent(applicationContext, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SECTION, MainActivity.SECTION_PROGRESS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val KEY_RECORD = "record"
        const val KEY_URL = "url"
        const val KEY_NAME = "name"
        const val KEY_HLS = "hls"
        const val KEY_REFERER = "referer"
        const val KEY_USER_AGENT = "user_agent"
        const val KEY_COOKIES = "cookies"
        const val KEY_MIME = "mime"
        const val KEY_PROGRESS = "progress"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_UNIT = "unit"
        const val KEY_URI = "uri"
        const val KEY_ERROR = "error"
        private const val PARALLEL_SEGMENTS = 4
    }
}
