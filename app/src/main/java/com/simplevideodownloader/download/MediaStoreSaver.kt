package com.simplevideodownloader.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.OutputStream
import kotlin.coroutines.resume

/**
 * Crea el archivo de destino en `Descargas/SimpleVideoDownloader`.
 * En Android 10+ usa MediaStore (no necesita permisos); en Android 7-9 escribe el archivo directamente.
 */
class MediaStoreSaver(private val context: Context) {

    class Output(val uri: Uri, val stream: OutputStream, internal val file: File?)

    fun create(fileName: String, mimeType: String): Output {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("No se pudo crear el archivo en Descargas")
            val stream = resolver.openOutputStream(uri) ?: error("No se pudo abrir el archivo")
            return Output(uri, stream, null)
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        dir.mkdirs()
        val file = uniqueFile(dir, fileName)
        return Output(file.toUri(), file.outputStream(), file)
    }

    /** Marca el archivo como terminado y devuelve la URI que pueden abrir otras apps. */
    suspend fun finish(output: Output, mimeType: String): Uri {
        output.stream.close()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            context.contentResolver.update(output.uri, values, null, null)
            return output.uri
        }
        val file = output.file!!
        return suspendCancellableCoroutine { cont ->
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mimeType)) { _, uri ->
                cont.resume(uri ?: output.uri)
            }
        }
    }

    fun abort(output: Output) {
        runCatching { output.stream.close() }
        if (output.file != null) output.file.delete() else runCatching { context.contentResolver.delete(output.uri, null, null) }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        var n = 1
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        while (file.exists()) {
            file = File(dir, if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext")
            n++
        }
        return file
    }

    companion object {
        const val FOLDER = "SimpleVideoDownloader"
    }
}
