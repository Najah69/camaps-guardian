package com.najah.camapsguardian.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Écriture d'un fichier dans Téléchargements (MediaStore, sans autorisation).
 * Avant Android 10 : dossier Téléchargements propre à l'appli, sans `Uri` partageable.
 */
object Downloads {

    class Saved(val label: String, val uri: Uri?)

    /** Lève une exception en cas d'échec. */
    fun save(ctx: Context, name: String, mime: String, bytes: ByteArray): Saved {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri)!!.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return Saved("${Environment.DIRECTORY_DOWNLOADS}/$name", uri)
        }
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: error("external storage unavailable")
        val out = File(dir, name)
        out.writeBytes(bytes)
        return Saved(out.absolutePath, null)
    }
}
