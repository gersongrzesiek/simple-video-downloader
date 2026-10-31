package com.simplevideodownloader.download

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import java.io.File

/** Genera la miniatura de un vídeo descargado y la guarda como JPEG. */
object Thumbnails {
    private const val FRAME_AT_US = 30_000_000L

    fun create(context: Context, uri: Uri, target: File): Boolean {
        val bitmap = fromRetriever(context, uri) ?: fromMediaStore(context, uri) ?: return false
        return runCatching {
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
            true
        }.getOrDefault(false)
    }

    /** Duración en milisegundos del vídeo guardado, o 0. */
    fun durationMs(context: Context, uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        } catch (_: Exception) {
            0
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Guarda una imagen ya descargada (p. ej. la miniatura de la página) como miniatura provisional. */
    fun save(bitmap: Bitmap, target: File): Boolean = runCatching {
        target.outputStream().use { scale(bitmap).compress(Bitmap.CompressFormat.JPEG, 80, it) }
        true
    }.getOrDefault(false)

    private fun fromRetriever(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            // El segundo 30 suele ser más representativo que el principio (logos, negro, intros).
            // Si el vídeo dura menos de 1:30, se toma su primer tercio.
            val durationUs = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) * 1000
            val atUs = if (durationUs > 0) minOf(FRAME_AT_US, durationUs / 3) else FRAME_AT_US
            val frame = retriever.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: return null
            scale(frame)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun fromMediaStore(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching { context.contentResolver.loadThumbnail(uri, Size(480, 270), null) }.getOrNull()
    }

    private fun scale(frame: Bitmap): Bitmap {
        val width = 480
        if (frame.width <= width) return frame
        val height = (frame.height * width.toFloat() / frame.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(frame, width, height, true)
    }
}
