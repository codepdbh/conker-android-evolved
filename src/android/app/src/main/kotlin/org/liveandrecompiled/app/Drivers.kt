package org.liveandrecompiled.app

import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipInputStream

// Vulkan drivers the player imports: the adrenotools ZIPs Turnip and others come in, a meta.json beside a .so. The app
// brings none. The choice is a file, read by the game's process (its own, so no shared preferences); the game opens the
// driver at start, and with none chosen, or one that won't open, it uses the phone's.
object Drivers {
    class Driver(val id: String, val name: String, val directory: File, val library: String)

    private const val SELECTED = "selected"
    private const val RECORD = "driver.json"
    private const val MAX_BYTES = 512L shl 20
    private const val MAX_FILES = 1024
    private val LIBRARY = Regex("[A-Za-z0-9_.+-]+\\.so")

    private fun root(context: Context) = File(context.filesDir, "gpu_drivers").apply { mkdirs() }

    fun list(context: Context): List<Driver> = root(context).listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
        .orEmpty().mapNotNull { dir ->
            try {
                val record = JSONObject(File(dir, RECORD).readText())
                val library = record.getString("libraryName")
                val payload = File(dir, "payload")
                if (LIBRARY.matches(library) && File(payload, library).isFile) {
                    Driver(dir.name, record.getString("name"), payload, library)
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }.sortedBy { it.name.lowercase() }

    fun selected(context: Context): Driver? {
        val file = File(root(context), SELECTED)
        if (!file.isFile) return null
        val id = file.readText().trim()
        return list(context).firstOrNull { it.id == id }
    }

    fun select(context: Context, driver: Driver?) {
        val file = File(root(context), SELECTED)
        if (driver == null) file.delete() else file.writeText(driver.id)
    }

    // what the game reads when it starts Vulkan (vulkan.cpp), none for the phone's own driver
    fun environment(context: Context): Map<String, String> {
        val driver = selected(context) ?: return emptyMap()
        return mapOf(
            "XBE_GPU_HOOKS" to context.applicationInfo.nativeLibraryDir + "/",
            "XBE_GPU_TEMP" to context.cacheDir.path + "/",
            "XBE_GPU_DRIVER_DIR" to driver.directory.path + "/",
            "XBE_GPU_DRIVER_LIB" to driver.library,
        )
    }

    fun import(context: Context, uri: Uri): Driver {
        val stage = File(root(context), ".import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException(Lang.t("No se pudo abrir el ZIP.", "Couldn't open the ZIP.", "Não foi possível abrir o ZIP."))
            input.use { extract(it, stage) }
            val found = findMeta(stage) ?: throw IOException(Lang.t("El ZIP no tiene meta.json: no es un paquete de driver.", "The ZIP has no meta.json: it isn't a driver package.", "O ZIP não tem meta.json: não é um pacote de driver."))
            val meta = JSONObject(File(found, "meta.json").readText())
            val library = meta.optString("libraryName")
            if (!LIBRARY.matches(library)) throw IOException(Lang.t("El meta.json no nombra una biblioteca válida.", "The meta.json names no valid library.", "O meta.json não indica uma biblioteca válida."))
            if (meta.optInt("minApi", 0) > Build.VERSION.SDK_INT) {
                throw IOException(Lang.t("Este driver necesita una versión más reciente de Android.", "This driver needs a newer version of Android.", "Este driver precisa de uma versão mais recente do Android."))
            }
            requireArm64(File(found, library))
            val name = meta.optString("name", library).trim().ifEmpty { library }.take(120)
            val home = File(root(context), UUID.randomUUID().toString()).apply { mkdirs() }
            if (!found.renameTo(File(home, "payload"))) throw IOException(Lang.t("No se pudo instalar el driver.", "Couldn't install the driver.", "Não foi possível instalar o driver."))
            File(home, RECORD).writeText(JSONObject().put("name", name).put("libraryName", library).toString())
            return Driver(home.name, name, File(home, "payload"), library)
        } finally {
            stage.deleteRecursively()
        }
    }

    fun remove(context: Context, driver: Driver) {
        if (selected(context)?.id == driver.id) select(context, null)
        File(root(context), driver.id).deleteRecursively()
    }

    // a ZIP is not to be trusted with where it writes, or how much
    private fun extract(input: java.io.InputStream, into: File) {
        val base = into.canonicalPath + File.separator
        var total = 0L
        var files = 0
        ZipInputStream(input).use { zip ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++files > MAX_FILES) throw IOException(Lang.t("El ZIP tiene demasiados archivos.", "The ZIP has too many files.", "O ZIP tem arquivos demais."))
                val file = File(into, entry.name)
                if (!file.canonicalPath.startsWith(base)) throw IOException(Lang.t("El ZIP tiene rutas no válidas.", "The ZIP has invalid paths.", "O ZIP tem caminhos inválidos."))
                if (entry.isDirectory) {
                    file.mkdirs()
                    continue
                }
                file.parentFile?.mkdirs()
                file.outputStream().use { out ->
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IOException(Lang.t("El paquete de driver es demasiado grande.", "The driver package is too large.", "O pacote de driver é grande demais."))
                        out.write(buffer, 0, n)
                    }
                }
            }
        }
    }

    private fun findMeta(dir: File): File? {
        if (File(dir, "meta.json").isFile) return dir
        val found = dir.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { findMeta(it) }
        if (found.size > 1) throw IOException(Lang.t("El ZIP tiene varios meta.json.", "The ZIP has several meta.json.", "O ZIP tem vários meta.json."))
        return found.firstOrNull()
    }

    // an ELF 64-bit little-endian shared object for AArch64
    private fun requireArm64(library: File) {
        if (!library.isFile) throw IOException(Lang.t("Falta ${library.name} en el ZIP.", "${library.name} is missing from the ZIP.", "Falta ${library.name} no ZIP."))
        val h = ByteArray(20)
        DataInputStream(library.inputStream()).use { it.readFully(h) }
        val ok = h[0].toInt() == 127 && h[1].toInt() == 'E'.code && h[2].toInt() == 'L'.code && h[3].toInt() == 'F'.code &&
            h[4].toInt() == 2 && h[5].toInt() == 1 && h[16].toInt() == 3 && h[17].toInt() == 0 &&
            (h[18].toInt() and 255) == 183 && h[19].toInt() == 0
        if (!ok) throw IOException(Lang.t("El driver debe ser una biblioteca Android ARM64 (.so).", "The driver must be an Android ARM64 library (.so).", "O driver deve ser uma biblioteca Android ARM64 (.so)."))
    }
}
