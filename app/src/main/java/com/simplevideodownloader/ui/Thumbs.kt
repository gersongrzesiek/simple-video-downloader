package com.simplevideodownloader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Caché en memoria de miniaturas: las listas se repintan a menudo (progreso) y así no parpadean. */
private val thumbCache = LruCache<String, Bitmap>(40)

/** Pinta la miniatura guardada en [file] (si existe), leyéndola en segundo plano la primera vez. */
fun ImageView.showThumbnail(file: File, scope: CoroutineScope) {
    val key = file.path + ":" + file.lastModified()
    tag = key
    thumbCache.get(key)?.let {
        setImageBitmap(it)
        return
    }
    setImageDrawable(null)
    scope.launch {
        val bitmap = withContext(Dispatchers.IO) { if (file.exists()) BitmapFactory.decodeFile(file.path) else null } ?: return@launch
        thumbCache.put(key, bitmap)
        if (tag == key) setImageBitmap(bitmap)
    }
}

/** Nombre para mostrar: el del archivo sin la extensión. */
fun displayName(fileName: String) = fileName.substringBeforeLast('.').ifBlank { fileName }

/** Si el vídeo descargado sigue en el teléfono (file:// o content://). Hace E/S: llamar fuera del hilo principal. */
fun mediaExists(context: Context, uri: Uri): Boolean =
    if (uri.scheme == "file") {
        uri.path?.let { File(it).exists() } == true
    } else {
        runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
    }
