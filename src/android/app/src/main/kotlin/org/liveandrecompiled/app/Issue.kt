package org.liveandrecompiled.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

// A problem report on the project's GitHub. The app holds no token, which anyone could take out of an APK: it opens GitHub's
// new issue page filled in, and the player, signed in, reviews it and sends it. Issues are public, so the text is shown
// before leaving and holds no settings or saves. The whole report doesn't fit a link: it goes to Downloads, to attach by hand.
object Issue {
    private const val NEW_ISSUE = "https://github.com/codepdbh/conker-android-evolved/issues/new"
    private const val MAX_ENCODED = 7000  // a longer link fails in some browsers and at GitHub
    private const val LOG_TAIL_BYTES = 256L shl 10
    private const val LOG_LINES = 40
    private val NOISE = Regex("pipeline creation took|compile took|dsound: effect")
    private val IMPORTANT = Regex("error|missing-data|\\[esp\\+|warp:", RegexOption.IGNORE_CASE)

    fun title(description: String): String {
        val first = description.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60)
        return "${Build.MODEL}: ${first ?: "problema"}"
    }

    // what the page will hold: the longest log that keeps the link within its limit
    fun body(context: Context, description: String, compat: Compat): String {
        var lines = logLines(context)
        while (true) {
            val text = compose(context, description, compat, lines)
            if (lines.isEmpty() || Uri.encode(text).length <= MAX_ENCODED) return text
            lines = lines.drop(maxOf(1, lines.size / 8))
        }
    }

    fun link(title: String, body: String): Uri = Uri.parse(NEW_ISSUE).buildUpon()
        .appendQueryParameter("title", title)
        .appendQueryParameter("body", body)
        .build()

    private fun compose(context: Context, description: String, compat: Compat, log: List<String>) = buildString {
        appendLine("## Qué pasó")
        appendLine(description.ifBlank { "(sin describir)" })
        appendLine()
        appendLine("## Teléfono")
        appendLine("```")
        append(Report.deviceInfo(context))
        appendLine("gpu=${compat.gpuName}, Vulkan ${compat.vulkan}")
        appendLine("```")
        appendLine()
        appendLine("## Final del log del juego")
        appendLine("```")
        log.forEach { appendLine(it) }
        appendLine("```")
    }

    // the newest log's last lines, without the ones that only count shaders and with the ones that say what went wrong
    private fun logLines(context: Context): List<String> {
        val newest = File(context.getExternalFilesDir(null), "logs").listFiles()?.filter { it.isFile }
            ?.maxByOrNull { it.lastModified() } ?: return emptyList()
        val all = String(Report.readTail(newest, LOG_TAIL_BYTES)).lines().filter { it.isNotBlank() && !NOISE.containsMatchIn(it) }
        val last = all.takeLast(LOG_LINES)
        return all.dropLast(last.size).filter { IMPORTANT.containsMatchIn(it) }.takeLast(LOG_LINES) + last
    }

    // the zip in Downloads, for the player to attach; false where Android gives no way without a permission (before 10)
    fun saveToDownloads(context: Context, zip: File): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, zip.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return resolver.openOutputStream(uri)?.use { out -> zip.inputStream().use { it.copyTo(out) }; true } ?: false
    }
}
