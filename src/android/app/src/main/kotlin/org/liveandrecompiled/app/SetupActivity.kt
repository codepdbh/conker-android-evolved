package org.liveandrecompiled.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

// The launcher, always the app's first screen: the state of the install and of this phone, picking the player's copy of
// the game, and the diagnostic report. Nothing here needs libmain.so to load, so it still opens, and can still send its
// logs, on a phone where the game can't start. The game runs in its own process (the manifest's :game): a crash there
// comes back here, with the report one tap away.
class SetupActivity : Activity() {
    private val gameFiles by lazy { File(filesDir, "assets") }
    private val sentinel by lazy { File(filesDir, GameActivity.RUNNING) }
    private val compat by lazy { Compat(Probe.text()) }
    private lateinit var status: TextView
    private lateinit var install: TextView
    private lateinit var crash: TextView
    private lateinit var phone: TextView
    private lateinit var bar: ProgressBar
    private lateinit var play: Button
    private lateinit var pick: List<Button>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(layout())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun installed() = File(gameFiles, READY).exists()

    // libmain.so is the game recompiled for this phone, absent from a launcher-only build
    private fun gameBuilt() = try {
        java.util.zip.ZipFile(applicationInfo.sourceDir).use { it.getEntry("lib/arm64-v8a/libmain.so") != null }
    } catch (_: Exception) {
        false
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun card() = GradientDrawable().apply {
        setColor(CARD)
        cornerRadius = dp(12).toFloat()
    }

    private fun layout(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
        }
        fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE) = TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        fun button(s: String, action: () -> Unit) = Button(this).apply {
            text = s
            isAllCaps = false
            setOnClickListener { action() }
        }
        fun panel(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            setPadding(dp(16), dp(14), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            views.forEach { addView(it) }
        }
        val info = packageManager.getPackageInfo(packageName, 0)
        column.addView(text(getString(R.string.app_name), 24f, true))
        column.addView(text("Port nativo ARM64 · versión ${info.versionName}", 13f, color = MUTED))
        crash = text("", 14f, true, WARN).apply { visibility = View.GONE }
        column.addView(crash)
        install = text("", 15f)
        play = button("Jugar") { startActivity(Intent(this, GameActivity::class.java)) }
        column.addView(panel(text("Juego", 13f, true, MUTED), install, play))
        val iso = button("Elegir imagen de disco (.iso)") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, PICK_ISO)
        }
        val folder = button("Elegir carpeta con el juego") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER)
        }
        pick = listOf(iso, folder)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        status = text("", 14f, color = MUTED)
        column.addView(panel(text("Tu copia del juego", 13f, true, MUTED), iso, folder, bar, status))
        phone = text("", 15f)
        column.addView(panel(text("Este teléfono", 13f, true, MUTED), phone,
            button("Ver compatibilidad") { showCompat() }))
        column.addView(panel(text("Controles táctiles", 13f, true, MUTED),
            button("Editar y probar controles") { startActivity(Intent(this, TouchEditorActivity::class.java)) }))
        column.addView(panel(text("Diagnóstico", 13f, true, MUTED),
            text("Crea un informe y lo envías por la app que elijas. No se envía nada automáticamente.", 13f, color = MUTED),
            button("Enviar informe y logs") { sendReport() },
            button("Qué incluye el informe") { showReportContents() }))
        return ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            addView(column)
        }
    }

    private fun refresh() {
        val built = gameBuilt()
        val have = installed()
        play.isEnabled = built && have && compat.verdict != Level.FAIL
        install.text = when {
            !built -> "Esta APK solo trae el launcher: el juego aún no está recompilado en ella.\n" +
                "Se puede comprobar el teléfono y validar tu copia, pero no jugar."
            !have -> "Elige tu copia del juego para instalarla en la memoria de la app."
            compat.verdict == Level.FAIL -> "El juego está instalado, pero este teléfono no cumple los requisitos."
            else -> "Listo para jugar."
        }
        phone.text = when (compat.verdict) {
            Level.OK -> "Cumple los requisitos: ${compat.gpuName}, Vulkan ${compat.vulkan}"
            Level.DEGRADED -> "Cumple los requisitos con funciones alternativas: ${compat.gpuName}, Vulkan ${compat.vulkan}"
            Level.FAIL -> "No cumple los requisitos: ${compat.gpuName}, Vulkan ${compat.vulkan}"
        }
        // the sentinel the game's activity left and never removed: it did not end by itself
        if (sentinel.exists()) {
            crash.text = "La última partida se cerró de forma inesperada. Envía el informe para saber por qué."
            crash.visibility = View.VISIBLE
        } else {
            crash.visibility = View.GONE
        }
    }

    private fun showCompat() {
        val body = buildString {
            for (c in compat.checks) {
                val mark = when (c.level) {
                    Level.OK -> "✓"
                    Level.DEGRADED -> "~"
                    Level.FAIL -> "✗"
                }
                appendLine("$mark ${c.name}: ${c.detail}")
            }
            appendLine()
            appendLine("Esto lee lo que el teléfono declara; solo jugar demuestra que todo funciona.")
        }
        AlertDialog.Builder(this).setTitle("Compatibilidad").setMessage(body)
            .setPositiveButton("Cerrar", null).show()
    }

    private fun showReportContents() {
        AlertDialog.Builder(this).setTitle("Qué incluye el informe").setMessage(
            "• Modelo del teléfono y versión de Android\n" +
                "• Capacidades de CPU y GPU\n" +
                "• Los logs de esta app y del juego\n" +
                "• Cómo terminaron las últimas partidas\n" +
                "• Tus ajustes del juego\n\n" +
                "No incluye los archivos del juego, tus partidas guardadas ni datos de otras apps.")
            .setPositiveButton("Cerrar", null).show()
    }

    private fun gameState() = buildString {
        appendLine("built=${gameBuilt()}")
        appendLine("installed=${installed()}")
        appendLine("sentinel_left=${sentinel.exists()}")
        appendLine("ready_sha1=${File(gameFiles, READY).takeIf { it.isFile }?.readText()}")
        appendLine("assets_bytes=${gameFiles.walkTopDown().filter { it.isFile }.sumOf { it.length() }}")
        appendLine("assets_files=${gameFiles.walkTopDown().count { it.isFile }}")
    }

    private fun sendReport() {
        say("Preparando el informe…")
        thread(name = "report") {
            val result: Any = try {
                Report.build(this, gameState())
            } catch (e: Exception) {
                e
            }
            runOnUiThread {
                if (result is File) {
                    say("Informe listo: ${result.length() / 1024} KB")
                    // sent, the warning has done its job
                    sentinel.delete()
                    refresh()
                    startActivity(Report.share(this, result))
                } else {
                    say("No se pudo crear el informe: $result")
                }
            }
        }
    }

    @Deprecated("Activity's own; the app uses no AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            PICK_ISO -> unpack { unpackIso(uri) }
            PICK_FOLDER -> unpack { unpackFolder(uri) }
        }
    }

    // a step on a thread of its own, the screen kept on; work returns "" or why it failed, and only clears the installed
    // game once the copy has been accepted
    private fun unpack(work: () -> String) {
        pick.forEach { it.isEnabled = false }
        play.isEnabled = false
        bar.visibility = View.VISIBLE
        bar.isIndeterminate = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        say("Comprobando tu copia…")
        thread(name = "unpack") {
            val error = try {
                work()
            } catch (e: Exception) {
                e.message ?: e.toString()
            }
            // a partly written game goes; one still whole (its marker there) was never touched
            if (error.isNotEmpty() && !installed()) gameFiles.deleteRecursively()
            runOnUiThread {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                bar.visibility = View.GONE
                pick.forEach { it.isEnabled = true }
                say(error.ifEmpty { "Juego instalado." })
                refresh()
            }
        }
    }

    // only a copy the build was made from: one the addresses in the recompiled code do not hold for would crash or,
    // worse, misbehave at random
    private fun refuse(sha: String): String? {
        if (sha in accepted()) return null
        return "Esta copia no es una versión que esta compilación admita.\n\n" +
            "SHA-1 de default.xbe: $sha\n\nNo se ha instalado nada."
    }

    private fun accepted(): Set<String> {
        val own = try {
            assets.open(ACCEPTED).bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        } catch (_: Exception) {
            emptyList()
        }
        return setOf(Releases.USA_DISC, Releases.STORE) + own
    }

    private fun room() = StatFs(filesDir.path).availableBytes

    private fun unpackIso(uri: Uri): String {
        val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return "No se pudo abrir el archivo."
        return pfd.use { p ->
            val disc = Disc.open(p)
            val xbe = disc.find("default.xbe") ?: return "La imagen no tiene default.xbe: no es el juego."
            val sha = disc.sha1(xbe)
            refuse(sha)?.let { return it }
            val need = disc.totalSize() + MARGIN
            if (room() < need) return "No hay espacio: hacen falta ${need shr 30} GB libres."
            gameFiles.deleteRecursively()
            gameFiles.mkdirs()
            say("Descomprimiendo el juego…")
            disc.extract(gameFiles, ::showProgress)
            File(gameFiles, READY).writeText(sha)
            ""
        }
    }

    private fun unpackFolder(uri: Uri): String {
        say("Buscando el juego en la carpeta…")
        val tree = Tree(contentResolver, uri)
        return when (val found = tree.find()) {
            null -> "No hay ningún juego en esta carpeta: necesita default.xbe y sus carpetas, una imagen .iso, " +
                "o DefaultPackage con DefaultPackage.data."
            is Found.Iso -> unpackIso(found.file.uri)
            is Found.Files -> {
                val xbe = tree.children(found.dir).first { it.name.equals("default.xbe", ignoreCase = true) }
                val sha = contentResolver.openInputStream(xbe.uri)!!.use { input ->
                    val md = java.security.MessageDigest.getInstance("SHA-1")
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        md.update(buf, 0, n)
                    }
                    md.digest().joinToString("") { "%02x".format(it) }
                }
                refuse(sha)?.let { return it }
                gameFiles.deleteRecursively()
                gameFiles.mkdirs()
                say("Copiando los archivos…")
                tree.copy(found.dir, gameFiles, ::showProgress)
                File(gameFiles, READY).writeText(sha)
                ""
            }
            is Found.Package -> unpackStore(found)
        }
    }

    // the digital release: its layout is read by the runtime's own code (src/setup/store.cpp), so libmain.so is needed
    private fun unpackStore(found: Found.Package): String {
        if (!gameBuilt()) return "Instalar la versión digital necesita el juego ya recompilado en la APK."
        val fds = found.data.map {
            contentResolver.openFileDescriptor(it.uri, "r") ?: return "${it.name} no se pudo abrir."
        }
        return try {
            gameFiles.deleteRecursively()
            gameFiles.mkdirs()
            val error = Native.unpackPackage(fds.map { it.fd }.toIntArray(), gameFiles.path) { done, total, text ->
                if (total > 0) showProgress(done, total)
                if (text.isNotEmpty()) say(text)
            }
            if (error.isNotEmpty()) return error
            val verified = Native.verify(gameFiles.path)
            if (verified.isEmpty()) File(gameFiles, READY).writeText(Native.expectedSha1())
            verified
        } finally {
            fds.forEach { it.close() }
        }
    }

    private fun showProgress(done: Long, total: Long) {
        if (total <= 0) return
        runOnUiThread {
            bar.isIndeterminate = false
            bar.progress = (done * 1000 / total).toInt()
        }
    }

    private fun say(text: String) = runOnUiThread { status.text = text }

    private companion object {
        const val PICK_ISO = 1
        const val PICK_FOLDER = 2
        const val READY = ".ready"
        const val ACCEPTED = "accepted_xbe.txt"
        const val MARGIN = 512L shl 20
        val BACKGROUND = Color.rgb(0x14, 0x17, 0x1B)
        val CARD = Color.rgb(0x20, 0x25, 0x2B)
        val MUTED = Color.rgb(0xA8, 0xB0, 0xB8)
        val WARN = Color.rgb(0xFF, 0xB7, 0x4D)
    }
}
