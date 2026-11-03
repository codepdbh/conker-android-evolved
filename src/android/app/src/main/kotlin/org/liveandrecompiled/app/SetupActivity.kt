package org.liveandrecompiled.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.view.Gravity
import android.view.InputDevice
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
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
    private lateinit var fps: List<Button>
    private lateinit var gameLanguages: LinearLayout
    private lateinit var gameLanguageNote: TextView
    private lateinit var choice: (String, () -> Unit) -> Button
    private lateinit var quality: List<Button>
    private lateinit var driver: TextView
    private lateinit var gamepad: TextView
    private lateinit var split: LinearLayout
    private lateinit var heroPane: LinearLayout
    private lateinit var sidePane: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // a modest phone starts on what suits it; the player's settings, once there, are left alone
        val fresh = !GameSettings.exists(this)
        Lang.load(this)
        if (fresh && compat.verdict == Level.DEGRADED) Quality.PERFORMANCE.apply(this)
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
        setStroke(dp(1), CARD_EDGE)
        cornerRadius = dp(16).toFloat()
    }

    // the same in each state but disabled, dimmed; a ripple over it for the touch
    private fun buttonBackground(primary: Boolean): Drawable {
        val radius = dp(14).toFloat()
        val on = if (primary) {
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACCENT, ACCENT_DEEP))
        } else {
            GradientDrawable().apply {
                setColor(BUTTON)
                setStroke(dp(1), CARD_EDGE)
            }
        }
        on.cornerRadius = radius
        val off = GradientDrawable().apply {
            setColor(BUTTON_OFF)
            cornerRadius = radius
        }
        val states = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), off)
            addState(intArrayOf(), on)
        }
        return RippleDrawable(ColorStateList.valueOf(RIPPLE), states, null)
    }

    private fun layout(): View {
        fun text(s: String, size: Float, bold: Boolean = false, color: Int = Color.WHITE) = TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        fun label(s: String) = text(s.uppercase(), 12f, true, ACCENT).apply { letterSpacing = 0.1f }
        fun button(s: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
            text = s
            isAllCaps = false
            textSize = if (primary) 18f else 15f
            typeface = Typeface.DEFAULT_BOLD
            stateListAnimator = null
            minHeight = dp(if (primary) 60 else 48)
            background = buttonBackground(primary)
            setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(TEXT_OFF, if (primary) ON_ACCENT else Color.WHITE)))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            setOnClickListener { action() }
        }
        fun panel(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = card()
            setPadding(dp(16), dp(14), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            views.forEach { addView(it) }
        }
        val info = packageManager.getPackageInfo(packageName, 0)
        heroPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        sidePane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroPane.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo)
            contentDescription = getString(R.string.app_name)
            layoutParams = LinearLayout.LayoutParams(dp(148), dp(148)).apply { bottomMargin = dp(10) }
        })
        heroPane.addView(text(getString(R.string.app_name), 24f, true).apply { gravity = Gravity.CENTER })
        heroPane.addView(text(Lang.t("Port nativo ARM64 · versión ${info.versionName}", "Native ARM64 port · version ${info.versionName}", "Port nativo ARM64 · versão ${info.versionName}"), 13f, color = MUTED).apply {
            gravity = Gravity.CENTER
        })
        crash = text("", 14f, true, WARN).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        heroPane.addView(crash)
        install = text("", 15f).apply { gravity = Gravity.CENTER }
        heroPane.addView(install)
        play = button(Lang.t("Jugar", "Play", "Jogar"), primary = true) { startActivity(Intent(this, GameActivity::class.java)) }
        heroPane.addView(play)
        val iso = button(Lang.t("Elegir imagen de disco (.iso)", "Choose disc image (.iso)", "Escolher imagem de disco (.iso)")) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }, PICK_ISO)
        }
        val folder = button(Lang.t("Elegir carpeta con el juego", "Choose folder with the game", "Escolher pasta com o jogo")) {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER)
        }
        pick = listOf(iso, folder)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(ACCENT)
            indeterminateTintList = ColorStateList.valueOf(ACCENT)
            visibility = View.GONE
        }
        status = text("", 14f, color = MUTED).apply { setPadding(0, dp(8), 0, 0) }
        phone = text("", 15f)
        gamepad = text("", 15f)
        fps = FrameRate.CHOICES.map { rate ->
            button("$rate FPS") {
                FrameRate.set(this, rate)
                refresh()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                    topMargin = dp(8)
                    marginEnd = if (rate == FrameRate.CHOICES.last()) 0 else dp(8)
                }
            }
        }
        val fpsRow = LinearLayout(this).apply { fps.forEach { addView(it) } }
        val qualityNames = listOf(Lang.t("Rendimiento", "Performance", "Desempenho") to Quality.PERFORMANCE, Lang.t("Equilibrado", "Balanced", "Equilibrado") to Quality.BALANCED, Lang.t("Calidad", "Quality", "Qualidade") to Quality.QUALITY)
        quality = qualityNames.map { (name, preset) ->
            button(name) {
                preset.apply(this)
                refresh()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                    topMargin = dp(8)
                    marginEnd = if (preset == Quality.QUALITY) 0 else dp(8)
                }
            }
        }
        val qualityRow = LinearLayout(this).apply { quality.forEach { addView(it) } }
        val languages = Lang.NAMES.mapIndexed { i, name ->
            button(name) {
                Lang.choose(this, i)
                recreate()
            }.apply {
                val on = i == Lang.current
                background = buttonBackground(on)
                setTextColor(if (on) ON_ACCENT else Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                    topMargin = dp(8)
                    marginEnd = if (i == Lang.NAMES.lastIndex) 0 else dp(8)
                }
            }
        }
        val languageRow = LinearLayout(this).apply { languages.forEach { addView(it) } }
        driver = text("", 15f)
        sidePane.addView(panel(label("FPS"), fpsRow,
            text(Lang.t("60 exige bastante más al teléfono.", "60 asks far more of the phone.", "60 exige bem mais do telefone."), 13f, color = MUTED).apply {
                setPadding(0, dp(8), 0, 0)
            }))
        sidePane.addView(panel(label(Lang.t("Calidad de imagen", "Picture quality", "Qualidade de imagem")), qualityRow,
            text(Lang.t("Rendimiento es lo más rápido, para teléfonos modestos. Se aplica al abrir el juego.", "Performance is fastest, for modest phones. It applies when the game opens.", "Desempenho é o mais rápido, para telefones modestos. Vale ao abrir o jogo."), 13f, color = MUTED).apply {
                setPadding(0, dp(8), 0, 0)
            }))
        sidePane.addView(panel(label(Lang.t("Idioma", "Language", "Idioma")), languageRow,
            text(Lang.t("Cambia el launcher y el menú del juego.", "Changes the launcher and the game's menu.", "Muda o launcher e o menu do jogo."), 13f, color = MUTED).apply {
                setPadding(0, dp(8), 0, 0)
            }))
        gameLanguages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        gameLanguageNote = text("", 13f, color = MUTED).apply { setPadding(0, dp(8), 0, 0) }
        choice = { name, action -> button(name, action = action) }
        sidePane.addView(panel(label(Lang.t("Idioma del juego", "Game language", "Idioma do jogo")), gameLanguages, gameLanguageNote))
        sidePane.addView(panel(label(Lang.t("Driver gráfico", "Graphics driver", "Driver gráfico")), driver,
            button(Lang.t("Elegir driver", "Choose driver", "Escolher driver")) { chooseDriver() },
            button(Lang.t("Importar driver (.zip)", "Import driver (.zip)", "Importar driver (.zip)")) {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, PICK_DRIVER)
            },
            text(Lang.t("Para drivers Vulkan en formato adrenotools, como Turnip. Un driver que no le sirva a tu GPU puede cerrar ", "For Vulkan drivers in adrenotools format, such as Turnip. A driver that doesn't suit your GPU can close ", "Para drivers Vulkan no formato adrenotools, como Turnip. Um driver que não sirva para sua GPU pode fechar ") +
                Lang.t("el juego: si pasa, vuelve aquí a \"Driver del teléfono\".", "the game: if so, come back here to \"Phone's driver\".", "o jogo: se acontecer, volte aqui em \"Driver do telefone\"."), 13f, color = MUTED).apply {
                setPadding(0, dp(8), 0, 0)
            }))
        sidePane.addView(panel(label(Lang.t("Tu copia del juego", "Your copy of the game", "Sua cópia do jogo")), iso, folder, bar, status))
        sidePane.addView(panel(label(Lang.t("Este teléfono", "This phone", "Este telefone")), phone, button(Lang.t("Ver compatibilidad", "See compatibility", "Ver compatibilidade")) { showCompat() }))
        sidePane.addView(panel(label(Lang.t("Mando", "Controller", "Controle")), gamepad,
            text(Lang.t("Conéctalo por Bluetooth o USB, también con el juego abierto. Guide, o Start y Back a la vez, abren el menú.",
                "Connect it by Bluetooth or USB, even with the game open. Guide, or Start and Back together, open the menu.",
                "Conecte por Bluetooth ou USB, mesmo com o jogo aberto. Guide, ou Start e Back juntos, abrem o menu."), 13f, color = MUTED)))
        sidePane.addView(panel(label(Lang.t("Controles táctiles", "Touch controls", "Controles de toque")),
            button(Lang.t("Editar y probar controles", "Edit and test controls", "Editar e testar controles")) { startActivity(Intent(this, TouchEditorActivity::class.java)) }))
        sidePane.addView(panel(label(Lang.t("Diagnóstico", "Diagnostics", "Diagnóstico")),
            text(Lang.t("Crea un informe y lo envías por la app que elijas. No se envía nada automáticamente.", "Makes a report you send with the app you choose. Nothing is sent automatically.", "Cria um relatório que você envia pelo app que escolher. Nada é enviado automaticamente."), 13f, color = MUTED),
            button(Lang.t("Reportar problema en GitHub", "Report a problem on GitHub", "Relatar problema no GitHub")) { describeIssue() },
            button(Lang.t("Enviar informe y logs", "Send report and logs", "Enviar relatório e logs")) { sendReport() },
            button(Lang.t("Qué incluye el informe", "What the report includes", "O que o relatório inclui")) { showReportContents() }))
        split = LinearLayout(this).apply {
            addView(heroPane)
            addView(sidePane)
        }
        arrange()
        return ScrollView(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(SKY_TOP, SKY_MIDDLE, SKY_BOTTOM))
            isFillViewport = true
            addView(split)
            // the app draws behind the system's bars and any notch: its content stays out from under them
            setOnApplyWindowInsetsListener { _, insets ->
                val (l, t, r, b) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    listOf(i.left, i.top, i.right, i.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    listOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight,
                        insets.systemWindowInsetBottom)
                }
                split.setPadding(dp(20) + l, dp(16) + t, dp(20) + r, dp(16) + b)
                insets
            }
        }
    }

    // two columns on their side, the art and Play to the left; one above the other upright
    private fun arrange() {
        val wide = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        split.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        heroPane.layoutParams = if (wide) {
            LinearLayout.LayoutParams(0, -2, 0.42f).apply { marginEnd = dp(20) }
        } else {
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
        }
        sidePane.layoutParams = if (wide) LinearLayout.LayoutParams(0, -2, 0.58f) else LinearLayout.LayoutParams(-1, -2)
    }

    // the manifest keeps the activity through a rotation, so an install under way keeps its progress
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        arrange()
    }

    private fun refresh() {
        val built = gameBuilt()
        val have = installed()
        val chosen = Drivers.selected(this)
        // the phone's own driver is what the check read: an imported one is the player's to try
        val fits = compat.verdict != Level.FAIL || chosen != null
        play.isEnabled = built && have && fits
        driver.text = chosen?.let { Lang.t("En uso: ${it.name}", "In use: ${it.name}", "Em uso: ${it.name}") } ?: Lang.t("En uso: driver del teléfono", "In use: phone's driver", "Em uso: driver do telefone")
        val rate = FrameRate.get(this)
        fps.forEachIndexed { i, b ->
            val on = FrameRate.CHOICES[i] == rate
            b.background = buttonBackground(on)
            b.setTextColor(if (on) ON_ACCENT else Color.WHITE)
        }
        showGameLanguages()
        val preset = Quality.current(this)
        quality.forEachIndexed { i, b ->
            val on = Quality.entries[i] == preset
            b.background = buttonBackground(on)
            b.setTextColor(if (on) ON_ACCENT else Color.WHITE)
        }
        install.text = when {
            !built -> Lang.t("Esta APK solo trae el launcher: el juego aún no está recompilado en ella.\n", "This APK only has the launcher: the game isn't recompiled in it yet.\n", "Este APK só traz o launcher: o jogo ainda não está recompilado nele.\n") +
                Lang.t("Se puede comprobar el teléfono y validar tu copia, pero no jugar.", "You can check the phone and validate your copy, but not play.", "Dá para verificar o telefone e validar sua cópia, mas não jogar.")
            !have -> Lang.t("Elige tu copia del juego para instalarla en la memoria de la app.", "Choose your copy of the game to install it in the app's storage.", "Escolha sua cópia do jogo para instalá-la na memória do app.")
            !fits -> Lang.t("El juego está instalado, pero este teléfono no cumple los requisitos.", "The game is installed, but this phone doesn't meet the requirements.", "O jogo está instalado, mas este telefone não atende aos requisitos.")
            else -> Lang.t("Listo para jugar.", "Ready to play.", "Pronto para jogar.")
        }
        phone.text = when (compat.verdict) {
            Level.OK -> Lang.t("Cumple los requisitos: ${compat.gpuName}, Vulkan ${compat.vulkan}", "Meets the requirements: ${compat.gpuName}, Vulkan ${compat.vulkan}", "Atende aos requisitos: ${compat.gpuName}, Vulkan ${compat.vulkan}")
            Level.DEGRADED -> Lang.t("Cumple los requisitos con funciones alternativas: ${compat.gpuName}, Vulkan ${compat.vulkan}", "Meets the requirements with fallbacks: ${compat.gpuName}, Vulkan ${compat.vulkan}", "Atende aos requisitos com alternativas: ${compat.gpuName}, Vulkan ${compat.vulkan}")
            Level.FAIL -> Lang.t("No cumple los requisitos: ${compat.gpuName}, Vulkan ${compat.vulkan}", "Doesn't meet the requirements: ${compat.gpuName}, Vulkan ${compat.vulkan}", "Não atende aos requisitos: ${compat.gpuName}, Vulkan ${compat.vulkan}")
        }
        phone.setTextColor(when (compat.verdict) {
            Level.OK -> GOOD
            Level.DEGRADED -> WARN
            Level.FAIL -> BAD
        })
        val pads = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
            .filter { !it.isVirtual && it.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD }.map { it.name }
        gamepad.text = if (pads.isEmpty()) Lang.t("Ninguno conectado: se usan los controles táctiles.",
            "None connected: the touch controls are used.", "Nenhum conectado: são usados os controles de toque.")
        else Lang.t("Conectado: ", "Connected: ", "Conectado: ") + pads.joinToString()
        gamepad.setTextColor(if (pads.isEmpty()) Color.WHITE else GOOD)
        // the sentinel the game's activity left and never removed: it did not end by itself
        if (sentinel.exists()) {
            crash.text = Lang.t("La última partida se cerró de forma inesperada. Envía el informe para saber por qué.", "The last game closed unexpectedly. Send the report to find out why.", "A última partida fechou inesperadamente. Envie o relatório para saber o motivo.")
            crash.visibility = View.VISIBLE
        } else {
            crash.visibility = View.GONE
        }
    }

    // three to a row, what the installed copy holds
    private fun showGameLanguages() {
        gameLanguages.removeAllViews()
        val languages = GameLanguage.installed(this)
        val current = GameLanguage.get(this)
        languages.chunked(3).forEach { row ->
            val line = LinearLayout(this)
            row.forEach { language ->
                line.addView(choice(language.name) {
                    GameLanguage.set(this, language.code)
                    refresh()
                }.apply {
                    val on = language.code == current
                    background = buttonBackground(on)
                    setTextColor(if (on) ON_ACCENT else Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                        topMargin = dp(8)
                        marginEnd = if (language === row.last()) 0 else dp(8)
                    }
                })
            }
            repeat(3 - row.size) { line.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f)) }
            gameLanguages.addView(line)
        }
        gameLanguageNote.text = when {
            languages.isEmpty() -> Lang.t("Instala tu copia para elegirlo.", "Install your copy to choose it.", "Instale sua cópia para escolher.")
            languages.size == 1 -> Lang.t("Tu copia solo trae este idioma.", "Your copy only has this language.", "Sua cópia só traz este idioma.")
            else -> Lang.t("Los textos del juego. Las voces son siempre las del disco. Se aplica al abrir el juego.",
                "The game's text. The voices are always the disc's. It applies when the game opens.",
                "Os textos do jogo. As vozes são sempre as do disco. Vale ao abrir o jogo.")
        }
    }

    private fun chooseDriver() {
        val drivers = Drivers.list(this)
        val names = (listOf(Lang.t("Driver del teléfono", "Phone's driver", "Driver do telefone")) + drivers.map { it.name }).toTypedArray()
        val current = drivers.indexOfFirst { it.id == Drivers.selected(this)?.id } + 1
        var at = current
        val dialog = AlertDialog.Builder(this).setTitle(Lang.t("Driver gráfico", "Graphics driver", "Driver gráfico"))
            .setSingleChoiceItems(names, current) { _, which -> at = which }
            .setPositiveButton(Lang.t("Usar", "Use", "Usar")) { _, _ ->
                Drivers.select(this, drivers.getOrNull(at - 1))
                refresh()
            }
            .setNegativeButton(Lang.t("Cancelar", "Cancel", "Cancelar"), null)
        if (drivers.isNotEmpty()) {
            dialog.setNeutralButton(Lang.t("Eliminar", "Delete", "Excluir")) { _, _ ->
                val gone = drivers.getOrNull(at - 1)
                if (gone == null) {
                    say(Lang.t("El driver del teléfono no se puede eliminar.", "The phone's driver can't be deleted.", "O driver do telefone não pode ser excluído."))
                } else {
                    Drivers.remove(this, gone)
                    say(Lang.t("Driver eliminado: ${gone.name}", "Driver deleted: ${gone.name}", "Driver excluído: ${gone.name}"))
                    refresh()
                }
            }
        }
        dialog.show()
    }

    private fun importDriver(uri: Uri) {
        say(Lang.t("Importando el driver…", "Importing the driver…", "Importando o driver…"))
        thread(name = "driver") {
            val message = try {
                val imported = Drivers.import(this, uri)
                Drivers.select(this, imported)
                Lang.t("Driver instalado y en uso: ${imported.name}", "Driver installed and in use: ${imported.name}", "Driver instalado e em uso: ${imported.name}")
            } catch (e: Exception) {
                Lang.t("No se pudo importar: ${e.message ?: e}", "Couldn't import: ${e.message ?: e}", "Não foi possível importar: ${e.message ?: e}")
            }
            runOnUiThread {
                say(message)
                refresh()
            }
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
            appendLine(Lang.t("Esto lee lo que el teléfono declara; solo jugar demuestra que todo funciona.", "This reads what the phone declares; only playing proves everything works.", "Isto lê o que o telefone declara; só jogando dá para saber se tudo funciona."))
        }
        AlertDialog.Builder(this).setTitle(Lang.t("Compatibilidad", "Compatibility", "Compatibilidade")).setMessage(body)
            .setPositiveButton(Lang.t("Cerrar", "Close", "Fechar"), null).show()
    }

    private fun showReportContents() {
        AlertDialog.Builder(this).setTitle(Lang.t("Qué incluye el informe", "What the report includes", "O que o relatório inclui")).setMessage(
            Lang.t("• Modelo del teléfono y versión de Android\n", "• Phone model and Android version\n", "• Modelo do telefone e versão do Android\n") +
                Lang.t("• Capacidades de CPU y GPU\n", "• CPU and GPU capabilities\n", "• Recursos de CPU e GPU\n") +
                Lang.t("• Los logs de esta app y del juego\n", "• The logs of this app and the game\n", "• Os logs deste app e do jogo\n") +
                Lang.t("• Cómo terminaron las últimas partidas\n", "• How the last games ended\n", "• Como terminaram as últimas partidas\n") +
                Lang.t("• Tus ajustes del juego\n\n", "• Your game settings\n\n", "• Seus ajustes do jogo\n\n") +
                Lang.t("No incluye los archivos del juego, tus partidas guardadas ni datos de otras apps.", "It doesn't include the game's files, your saves or data from other apps.", "Não inclui os arquivos do jogo, seus jogos salvos nem dados de outros apps."))
            .setPositiveButton(Lang.t("Cerrar", "Close", "Fechar"), null).show()
    }

    private fun gameState() = buildString {
        appendLine("built=${gameBuilt()}")
        appendLine("installed=${installed()}")
        appendLine("sentinel_left=${sentinel.exists()}")
        appendLine("ready_sha1=${File(gameFiles, READY).takeIf { it.isFile }?.readText()}")
        appendLine("assets_bytes=${gameFiles.walkTopDown().filter { it.isFile }.sumOf { it.length() }}")
        appendLine("assets_files=${gameFiles.walkTopDown().count { it.isFile }}")
    }

    private fun describeIssue() {
        val box = EditText(this).apply {
            hint = Lang.t("Qué estabas haciendo y qué pasó", "What you were doing and what happened", "O que você estava fazendo e o que aconteceu")
            minLines = 3
        }
        AlertDialog.Builder(this).setTitle(Lang.t("Reportar problema en GitHub", "Report a problem on GitHub", "Relatar problema no GitHub")).setView(box)
            .setPositiveButton(Lang.t("Revisar", "Review", "Revisar")) { _, _ -> reviewIssue(box.text.toString()) }
            .setNegativeButton(Lang.t("Cancelar", "Cancel", "Cancelar"), null).show()
    }

    // the exact text before anything leaves: an issue is public
    private fun reviewIssue(description: String) {
        say(Lang.t("Preparando el informe…", "Preparing the report…", "Preparando o relatório…"))
        thread(name = "issue") {
            val result = try {
                Issue.body(this, description, compat) to Report.build(this, gameState())
            } catch (e: Exception) {
                say(Lang.t("No se pudo crear el informe: $e", "Couldn't make the report: $e", "Não foi possível criar o relatório: $e"))
                return@thread
            }
            runOnUiThread {
                say("")
                val (body, zip) = result
                AlertDialog.Builder(this).setTitle(Lang.t("Se publicará esto, a la vista de todos", "This will be published, for everyone to see", "Isto será publicado, à vista de todos"))
                    .setMessage(body + Lang.t("\n\nSe abrirá GitHub con este texto, que aún podrás cambiar. Necesitas una cuenta, ", "\n\nGitHub will open with this text, which you can still change. You need an account, ", "\n\nO GitHub abrirá com este texto, que você ainda pode mudar. Você precisa de uma conta, ") +
                        Lang.t("y no se envía hasta que lo confirmes allí.", "and nothing is sent until you confirm there.", "e nada é enviado até você confirmar lá."))
                    .setPositiveButton(Lang.t("Abrir GitHub", "Open GitHub", "Abrir GitHub")) { _, _ -> openIssue(description, body, zip) }
                    .setNegativeButton(Lang.t("Cancelar", "Cancel", "Cancelar"), null).show()
            }
        }
    }

    private fun openIssue(description: String, body: String, zip: File) {
        val saved = try {
            Issue.saveToDownloads(this, zip)
        } catch (_: Exception) {
            false
        }
        say(if (saved) Lang.t("El informe completo está en Descargas (${zip.name}). Si quieres, adjúntalo en la issue; también será público.", "The full report is in Downloads (${zip.name}). Attach it to the issue if you like; it will be public too.", "O relatório completo está em Downloads (${zip.name}). Anexe à issue se quiser; também será público.") else "")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Issue.link(Issue.title(description), body)))
            sentinel.delete()
            refresh()
        } catch (_: ActivityNotFoundException) {
            say(Lang.t("No hay ningún navegador para abrir GitHub.", "There's no browser to open GitHub.", "Não há navegador para abrir o GitHub."))
        }
    }

    private fun sendReport() {
        say(Lang.t("Preparando el informe…", "Preparing the report…", "Preparando o relatório…"))
        thread(name = "report") {
            val result: Any = try {
                Report.build(this, gameState())
            } catch (e: Exception) {
                e
            }
            runOnUiThread {
                if (result is File) {
                    say(Lang.t("Informe listo: ${result.length() / 1024} KB", "Report ready: ${result.length() / 1024} KB", "Relatório pronto: ${result.length() / 1024} KB"))
                    // sent, the warning has done its job
                    sentinel.delete()
                    refresh()
                    startActivity(Report.share(this, result))
                } else {
                    say(Lang.t("No se pudo crear el informe: $result", "Couldn't make the report: $result", "Não foi possível criar o relatório: $result"))
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
            PICK_DRIVER -> importDriver(uri)
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
        say(Lang.t("Comprobando tu copia…", "Checking your copy…", "Verificando sua cópia…"))
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
                if (error.isEmpty()) GameLanguage.followApp(this)
                say(error.ifEmpty { Lang.t("Juego instalado.", "Game installed.", "Jogo instalado.") })
                refresh()
            }
        }
    }

    // only a copy the build was made from: one the addresses in the recompiled code do not hold for would crash or,
    // worse, misbehave at random
    private fun refuse(sha: String): String? {
        if (sha in accepted()) return null
        return Lang.t("Esta copia no es una versión que esta compilación admita.\n\n", "This copy isn't a version this build supports.\n\n", "Esta cópia não é uma versão que esta compilação aceita.\n\n") +
            Lang.t("SHA-1 de default.xbe: $sha\n\nNo se ha instalado nada.", "default.xbe SHA-1: $sha\n\nNothing was installed.", "SHA-1 de default.xbe: $sha\n\nNada foi instalado.")
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
        val pfd = contentResolver.openFileDescriptor(uri, "r") ?: return Lang.t("No se pudo abrir el archivo.", "Couldn't open the file.", "Não foi possível abrir o arquivo.")
        return pfd.use { p ->
            val disc = Disc.open(p)
            val xbe = disc.find("default.xbe") ?: return Lang.t("La imagen no tiene default.xbe: no es el juego.", "The image has no default.xbe: it isn't the game.", "A imagem não tem default.xbe: não é o jogo.")
            val sha = disc.sha1(xbe)
            refuse(sha)?.let { return it }
            val need = disc.totalSize() + MARGIN
            if (room() < need) return Lang.t("No hay espacio: hacen falta ${need shr 30} GB libres.", "Not enough room: ${need shr 30} GB free are needed.", "Sem espaço: são necessários ${need shr 30} GB livres.")
            gameFiles.deleteRecursively()
            gameFiles.mkdirs()
            say(Lang.t("Descomprimiendo el juego…", "Unpacking the game…", "Descompactando o jogo…"))
            disc.extract(gameFiles, ::showProgress)
            File(gameFiles, READY).writeText(sha)
            ""
        }
    }

    private fun unpackFolder(uri: Uri): String {
        say(Lang.t("Buscando el juego en la carpeta…", "Looking for the game in the folder…", "Procurando o jogo na pasta…"))
        val tree = Tree(contentResolver, uri)
        return when (val found = tree.find()) {
            null -> Lang.t("No hay ningún juego en esta carpeta: necesita default.xbe y sus carpetas, una imagen .iso, ", "There's no game in this folder: it needs default.xbe and its folders, a .iso image, ", "Não há jogo nesta pasta: precisa de default.xbe e suas pastas, uma imagem .iso, ") +
                Lang.t("o DefaultPackage con DefaultPackage.data.", "or DefaultPackage with DefaultPackage.data.", "ou DefaultPackage com DefaultPackage.data.")
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
                say(Lang.t("Copiando los archivos…", "Copying the files…", "Copiando os arquivos…"))
                tree.copy(found.dir, gameFiles, ::showProgress)
                File(gameFiles, READY).writeText(sha)
                ""
            }
            is Found.Package -> unpackStore(found)
        }
    }

    // the digital release: its layout is read by the runtime's own code (src/setup/store.cpp), so libmain.so is needed
    private fun unpackStore(found: Found.Package): String {
        if (!gameBuilt()) return Lang.t("Instalar la versión digital necesita el juego ya recompilado en la APK.", "Installing the digital version needs the game already recompiled in the APK.", "Instalar a versão digital requer o jogo já recompilado no APK.")
        val fds = found.data.map {
            contentResolver.openFileDescriptor(it.uri, "r") ?: return Lang.t("${it.name} no se pudo abrir.", "${it.name} couldn't be opened.", "${it.name} não pôde ser aberto.")
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
        const val PICK_DRIVER = 3
        const val READY = ".ready"
        const val ACCEPTED = "accepted_xbe.txt"
        const val MARGIN = 512L shl 20
        // the icon's: its army green, its fur and fire
        const val SKY_TOP = 0xFF11160B.toInt()
        const val SKY_MIDDLE = 0xFF2A3318.toInt()
        const val SKY_BOTTOM = 0xFF160E07.toInt()
        const val CARD = 0xCC1B2211.toInt()
        const val CARD_EDGE = 0x665F7A2E
        const val BUTTON = 0x33748F3A
        const val BUTTON_OFF = 0x14FFFFFF
        const val TEXT_OFF = 0x66FFFFFF
        const val RIPPLE = 0x40FFFFFF
        const val ACCENT = 0xFFFFB13D.toInt()
        const val ACCENT_DEEP = 0xFFE8641A.toInt()
        const val ON_ACCENT = 0xFF241004.toInt()
        const val MUTED = 0xFFB9C0A8.toInt()
        const val GOOD = 0xFF9CD67A.toInt()
        const val WARN = 0xFFFFC857.toInt()
        const val BAD = 0xFFFF6B6B.toInt()
    }
}
