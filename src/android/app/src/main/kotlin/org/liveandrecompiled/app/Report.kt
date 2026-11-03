package org.liveandrecompiled.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// The diagnostic report: one zip the player sends where they choose (Android's share sheet) or saves. Nothing leaves the
// phone on its own. In it: the phone's model and Android version, what the probe found, the app's own log lines
// (logcat only shows an app its own), the game's logs and how the last runs ended. Not in it: the game's files, saves,
// accounts or anything from other apps.
object Report {
    private const val MAX_LOG_BYTES = 8L shl 20
    private const val LOGCAT_LINES = "30000"

    fun deviceInfo(context: Context): String = buildString {
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        appendLine("app=${context.packageName} ${info.versionName} (${info.longVersionCode})")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("soc=${if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE}")
        appendLine("board=${Build.BOARD}")
        appendLine("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("kernel=${System.getProperty("os.version")}")
        appendLine("abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
        appendLine("ram_total_mb=${memory.totalMem shr 20}")
        appendLine("ram_free_mb=${memory.availMem shr 20}")
        appendLine("storage_free_mb=${StatFs(context.filesDir.path).availableBytes shr 20}")
    }

    fun exitReasons(context: Context): String = buildString {
        if (Build.VERSION.SDK_INT < 30) {
            appendLine("(Android ${Build.VERSION.RELEASE} no guarda el motivo de salida de los procesos)")
            return@buildString
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val when_ = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        for (e in am.getHistoricalProcessExitReasons(context.packageName, 0, 10)) {
            appendLine("${when_.format(Date(e.timestamp))} ${e.processName} reason=${reasonName(e.reason)} " +
                "status=${e.status} importance=${e.importance} pss=${e.pss}kB rss=${e.rss}kB ${e.description ?: ""}")
        }
    }

    private fun reasonName(reason: Int) = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "crash (Java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash (nativo)"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "sin memoria (LMK)"
        ApplicationExitInfo.REASON_EXIT_SELF -> "salida normal"
        ApplicationExitInfo.REASON_SIGNALED -> "señal"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "cerrada por el usuario"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependencia"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "fallo al iniciar"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permiso cambiado"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "uso excesivo"
        ApplicationExitInfo.REASON_OTHER -> "otro"
        else -> reason.toString()
    }

    // the zip, in the cache's reports folder (where ReportProvider serves from); older reports go
    fun build(context: Context, gameState: String): File {
        val dir = File(context.cacheDir, "reports")
        dir.deleteRecursively()
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val file = File(dir, "conker-informe-$stamp.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("device.txt", deviceInfo(context).toByteArray())
            put("probe.txt", Probe.text().toByteArray())
            put("state.txt", gameState.toByteArray())
            put("exit-reasons.txt", exitReasons(context).toByteArray())
            put("logcat.txt", logcat())
            addGameLogs(context, ::put)
            addTombstones(context, ::put)
            File(context.filesDir, "settings.toml").takeIf { it.isFile }?.let { put("settings.toml", it.readBytes()) }
        }
        return file
    }

    private fun logcat(): ByteArray = try {
        val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", LOGCAT_LINES).redirectErrorStream(true).start()
        p.inputStream.use { it.readBytes() }
    } catch (e: Exception) {
        "no se pudo leer logcat: $e\n".toByteArray()
    }

    // the game's logs, newest first, up to the size limit
    private fun addGameLogs(context: Context, put: (String, ByteArray) -> Unit) {
        val logs = File(context.getExternalFilesDir(null), "logs")
        var left = MAX_LOG_BYTES
        logs.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.forEach { f ->
            if (left <= 0) return
            val tail = readTail(f, left)
            left -= tail.size
            put("game-logs/${f.name}", tail)
        }
    }

    fun readTail(f: File, max: Long): ByteArray = java.io.RandomAccessFile(f, "r").use { r ->
        val n = minOf(r.length(), max)
        r.seek(r.length() - n)
        ByteArray(n.toInt()).also { r.readFully(it) }
    }

    // the system's crash report (the tombstone), which Android 12 and later hand to the app it was for
    private fun addTombstones(context: Context, put: (String, ByteArray) -> Unit) {
        if (Build.VERSION.SDK_INT < 31) return
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            .filter { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE }
            .forEachIndexed { i, e ->
                try {
                    e.traceInputStream?.use { put("tombstones/tombstone-$i.pb", it.readBytes()) }
                } catch (_: Exception) {
                }
            }
    }

    fun share(context: Context, file: File): Intent {
        val uri = ReportProvider.uri(context, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Informe de Conker Android Evolved")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Enviar informe")
    }
}

// Serves a report to the app the player sent it to: the reports folder only, read only, by a grant for that one send.
// Written out instead of AndroidX's FileProvider, as the app has no AndroidX.
class ReportProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileOf(uri: Uri): File {
        val context = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        val dir = File(context.cacheDir, "reports").canonicalFile
        val file = File(dir, name).canonicalFile
        if (file.parentFile != dir || !file.isFile) throw FileNotFoundException(name)
        return file
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        return ParcelFileDescriptor.open(fileOf(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
                       sortOrder: String?): Cursor {
        val file = fileOf(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = columns.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        return MatrixCursor(columns).apply { addRow(row) }
    }

    override fun getType(uri: Uri) = "application/zip"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        fun uri(context: Context, file: File): Uri =
            Uri.parse("content://${context.packageName}.reports/${Uri.encode(file.name)}")
    }
}
