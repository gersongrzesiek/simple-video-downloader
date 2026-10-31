package com.simplevideodownloader.download

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.simplevideodownloader.data.DownloadRecord
import com.simplevideodownloader.data.DownloadStore
import com.simplevideodownloader.detect.MediaClassifier
import java.util.concurrent.TimeUnit

/** Lo que hace falta para descargar algo: la URL y las cabeceras con las que la pidió la página. */
data class DownloadRequest(
    val url: String,
    val fileName: String,
    val isHls: Boolean,
    val referer: String? = null,
    val userAgent: String? = null,
    val cookies: String? = null,
    val mimeType: String? = null,
)

object Downloads {
    const val TAG = "download"
    const val TAG_NAME_PREFIX = "name:"
    const val TAG_RECORD_PREFIX = "record:"

    /** Crea la descarga en el historial y la pone en cola. */
    fun enqueue(context: Context, request: DownloadRequest, pageUrl: String?, quality: String?): DownloadRecord {
        val record = DownloadStore.get(context).create(request, pageUrl, quality)
        startWork(context, record.id, request)
        return record
    }

    /** Vuelve a descargar una entrada del historial (p. ej. si se borró el archivo o falló). */
    fun restart(context: Context, record: DownloadRecord) {
        DownloadStore.get(context).update(record.id) {
            it.copy(status = DownloadRecord.Status.ACTIVE, uri = null, sizeBytes = -1, completedAt = 0, error = null)
        }
        startWork(context, record.id, record.request)
    }

    private fun startWork(context: Context, recordId: String, request: DownloadRequest) {
        val work = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_RECORD to recordId,
                    DownloadWorker.KEY_URL to request.url,
                    DownloadWorker.KEY_NAME to request.fileName,
                    DownloadWorker.KEY_HLS to request.isHls,
                    DownloadWorker.KEY_REFERER to request.referer,
                    DownloadWorker.KEY_USER_AGENT to request.userAgent,
                    DownloadWorker.KEY_COOKIES to request.cookies,
                    DownloadWorker.KEY_MIME to request.mimeType,
                )
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .addTag(TAG_NAME_PREFIX + request.fileName)
            .addTag(TAG_RECORD_PREFIX + recordId)
            .build()
        WorkManager.getInstance(context).enqueue(work)
    }

    /** Nombre de archivo legible a partir del título de la página o de la URL. */
    fun suggestFileName(title: String?, url: String, isHls: Boolean, mimeType: String? = null): String {
        val ext = when {
            isHls -> "ts"
            else -> MediaClassifier.extensionOf(url).takeIf { it.isNotEmpty() && it.length <= 4 }
                ?: mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
                ?: "mp4"
        }
        val fromUrl = url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.')
        val base = (title?.takeIf { it.isNotBlank() } ?: fromUrl.ifBlank { "video" })
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(80)
            .ifBlank { "video" }
        return "$base.$ext"
    }
}
