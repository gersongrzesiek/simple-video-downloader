package com.simplevideodownloader.data

import android.content.Context
import com.simplevideodownloader.download.DownloadRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Una descarga, con lo necesario para mostrarla, reproducirla o volver a bajarla. */
data class DownloadRecord(
    val id: String,
    val request: DownloadRequest,
    val pageUrl: String?,
    val quality: String?,
    val createdAt: Long,
    val status: Status = Status.ACTIVE,
    val uri: String? = null,
    val sizeBytes: Long = -1,
    val completedAt: Long = 0,
    val error: String? = null,
    /** Duración del vídeo terminado, en milisegundos (0 si no se sabe). */
    val durationMs: Long = 0,
) {
    enum class Status { ACTIVE, DONE, FAILED }
}

/** Historial de descargas guardado en un JSON privado. La interfaz observa [records]. */
class DownloadStore private constructor(private val context: Context) {
    private val file = JsonFile(File(context.filesDir, "downloads.json"))
    private val items = LinkedHashMap<String, DownloadRecord>()

    private val _records = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val records: StateFlow<List<DownloadRecord>> = _records

    init {
        load()
        publish()
    }

    @Synchronized
    fun create(request: DownloadRequest, pageUrl: String?, quality: String?): DownloadRecord {
        val record = DownloadRecord(UUID.randomUUID().toString(), request, pageUrl, quality, System.currentTimeMillis())
        items[record.id] = record
        save()
        return record
    }

    @Synchronized
    fun get(id: String): DownloadRecord? = items[id]

    @Synchronized
    fun update(id: String, change: (DownloadRecord) -> DownloadRecord) {
        val current = items[id] ?: return
        items[id] = change(current)
        save()
    }

    @Synchronized
    fun remove(id: String) {
        items.remove(id)
        thumbnailFile(id).delete()
        save()
    }

    fun thumbnail(record: DownloadRecord): File = thumbnailFile(record.id)

    fun thumbnailFile(id: String): File = File(File(context.filesDir, "thumbs").apply { mkdirs() }, "$id.jpg")

    private fun load() {
        val text = file.read() ?: return
        runCatching {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val r = o.getJSONObject("request")
                val record = DownloadRecord(
                    id = o.getString("id"),
                    request = DownloadRequest(
                        url = r.getString("url"),
                        fileName = r.getString("fileName"),
                        isHls = r.optBoolean("isHls"),
                        referer = r.optStringOrNull("referer"),
                        userAgent = r.optStringOrNull("userAgent"),
                        cookies = r.optStringOrNull("cookies"),
                        mimeType = r.optStringOrNull("mimeType"),
                    ),
                    pageUrl = o.optStringOrNull("pageUrl"),
                    quality = o.optStringOrNull("quality"),
                    createdAt = o.optLong("createdAt"),
                    status = runCatching { DownloadRecord.Status.valueOf(o.getString("status")) }.getOrDefault(DownloadRecord.Status.FAILED),
                    uri = o.optStringOrNull("uri"),
                    sizeBytes = o.optLong("sizeBytes", -1),
                    completedAt = o.optLong("completedAt"),
                    error = o.optStringOrNull("error"),
                    durationMs = o.optLong("durationMs"),
                )
                items[record.id] = record
            }
        }
    }

    private fun save() {
        val arr = JSONArray()
        items.values.forEach { rec ->
            val r = rec.request
            arr.put(
                JSONObject()
                    .put("id", rec.id)
                    .put("request", JSONObject()
                        .put("url", r.url).put("fileName", r.fileName).put("isHls", r.isHls)
                        .putOpt("referer", r.referer).putOpt("userAgent", r.userAgent)
                        .putOpt("cookies", r.cookies).putOpt("mimeType", r.mimeType))
                    .putOpt("pageUrl", rec.pageUrl)
                    .putOpt("quality", rec.quality)
                    .put("createdAt", rec.createdAt)
                    .put("status", rec.status.name)
                    .putOpt("uri", rec.uri)
                    .put("sizeBytes", rec.sizeBytes)
                    .put("completedAt", rec.completedAt)
                    .putOpt("error", rec.error)
                    .put("durationMs", rec.durationMs)
            )
        }
        file.write(arr.toString())
        publish()
    }

    private fun publish() {
        _records.value = items.values.sortedByDescending { maxOf(it.completedAt, it.createdAt) }
    }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null

    companion object {
        @Volatile private var instance: DownloadStore? = null

        fun get(context: Context): DownloadStore =
            instance ?: synchronized(this) { instance ?: DownloadStore(context.applicationContext).also { instance = it } }
    }
}
