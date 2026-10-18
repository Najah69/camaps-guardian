package com.najah.camapsguardian.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Journal circulaire : mémoire (interface) + fichier (survit aux redémarrages du service).
 */
object Logbook {

    private const val TAG = "CamAPSGuardian"
    private const val MAX = 600
    private const val FILE_NAME = "journal.txt"
    private const val ROTATE_AT = 150_000L

    private val lines = ArrayDeque<String>()
    private val _flow = MutableStateFlow<List<String>>(emptyList())
    val flow: StateFlow<List<String>> = _flow

    @Volatile
    private var file: File? = null
    private val stamp = SimpleDateFormat("dd/MM HH:mm:ss", Locale.FRANCE)

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, FILE_NAME)
        file = f
        runCatching {
            if (f.exists()) f.readLines().takeLast(MAX).forEach { lines.addLast(it) }
        }
        _flow.value = lines.toList()
    }

    @Synchronized
    fun add(tag: String, message: String) {
        val line = "${stamp.format(Date())} [$tag] $message"
        lines.addLast(line)
        while (lines.size > MAX) lines.removeFirst()
        _flow.value = lines.toList()
        Log.i(TAG, "$tag : $message")
        val f = file
        if (f != null) {
            runCatching {
                f.appendText(line + "\n")
                if (f.length() > ROTATE_AT) {
                    f.writeText(
                        lines.toList().takeLast(MAX / 2).joinToString("\n", postfix = "\n")
                    )
                }
            }
        }
    }

    @Synchronized
    fun clear() {
        lines.clear()
        _flow.value = emptyList()
        runCatching { file?.writeText("") }
    }
}
