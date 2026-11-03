package org.liveandrecompiled.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.min

// A control of the on-screen controller, as src/runtime/app/touch.cpp draws it. id is the name in touch_layout.txt; x, y
// and r are its default place in units of the short side's tenth, from the corner in `anchor`.
private class Control(val id: String, val label: String, val def: (w: Float, h: Float, u: Float) -> Triple<Float, Float, Float>) {
    var x = 0f  // fractions of the screen
    var y = 0f
    var size = 1f
    var moved = false
}

// the same numbers as Layout() in touch.cpp
private fun defaults(): List<Control> {
    fun face(dx: Float, dy: Float, r: Float) = { w: Float, h: Float, u: Float ->
        Triple(w - 2.3f * u + dx * u, h - 2.3f * u + dy * u, r * u)
    }
    fun pad(dx: Float, dy: Float) = { _: Float, h: Float, u: Float ->
        Triple(1.9f * u + dx * u, h * 0.40f + dy * u, 0.42f * u)
    }
    return listOf(
        Control("a", "A", face(0f, 1f, 0.62f)),
        Control("b", "B", face(1f, 0f, 0.62f)),
        Control("x", "X", face(-1f, 0f, 0.62f)),
        Control("y", "Y", face(0f, -1f, 0.62f)),
        Control("white", "W", face(-1.6f, -2f, 0.45f)),
        Control("black", "Bk", face(0.2f, -2.4f, 0.45f)),
        Control("lt", "LT") { _, _, u -> Triple(1.3f * u, 1.3f * u, 0.75f * u) },
        Control("rt", "RT") { w, _, u -> Triple(w - 1.3f * u, 1.3f * u, 0.75f * u) },
        Control("up", "↑", pad(0f, -0.75f)),
        Control("down", "↓", pad(0f, 0.75f)),
        Control("left", "←", pad(-0.75f, 0f)),
        Control("right", "→", pad(0.75f, 0f)),
        Control("back", "Back") { w, _, u -> Triple(w / 2 - 1.5f * u, 0.7f * u, 0.45f * u) },
        Control("start", "Start") { w, _, u -> Triple(w / 2 + 1.5f * u, 0.7f * u, 0.45f * u) },
        Control("menu", "≡") { w, _, u -> Triple(w / 2, 0.7f * u, 0.45f * u) },
    )
}

// touch_layout.txt, the file touch.cpp reads when the game starts
private class LayoutFile(context: Context) {
    private val file = File(context.filesDir, "touch_layout.txt")
    val controls = defaults()
    var opacity = 1f
    var hideWithPad = true
    var vibrate = true

    fun load() {
        if (!file.isFile) return
        for (line in file.readLines()) {
            val p = line.trim().split(' ')
            when {
                p.size == 2 && p[0] == "opacity" -> opacity = p[1].toFloatOrNull()?.coerceIn(0.1f, 1f) ?: opacity
                p.size == 2 && p[0] == "hide_with_pad" -> hideWithPad = p[1] != "0"
                p.size == 2 && p[0] == "vibrate" -> vibrate = p[1] != "0"
                p.size == 4 -> controls.firstOrNull { it.id == p[0] }?.let { c ->
                    c.x = p[1].toFloatOrNull()?.coerceIn(0f, 1f) ?: return@let
                    c.y = p[2].toFloatOrNull()?.coerceIn(0f, 1f) ?: return@let
                    c.size = p[3].toFloatOrNull()?.coerceIn(MIN_SIZE, MAX_SIZE) ?: return@let
                    c.moved = true
                }
            }
        }
    }

    fun save() {
        file.writeText(buildString {
            for (c in controls.filter { it.moved }) {
                appendLine(String.format(Locale.ROOT, "%s %.4f %.4f %.3f", c.id, c.x, c.y, c.size))
            }
            appendLine(String.format(Locale.ROOT, "opacity %.2f", opacity))
            appendLine("hide_with_pad ${if (hideWithPad) 1 else 0}")
            appendLine("vibrate ${if (vibrate) 1 else 0}")
        })
    }

    fun reset() {
        file.delete()
        controls.forEach {
            it.moved = false
            it.size = 1f
        }
        opacity = 1f
        hideWithPad = true
        vibrate = true
    }

    companion object {
        const val MIN_SIZE = 0.4f
        const val MAX_SIZE = 2.5f
    }
}

// The canvas: in edit mode a finger drags a control and a second one on it pinches its size; in test mode every finger
// presses what it lands on, so that several at once can be seen working without the game.
private class EditorView(context: Context, val layout: LayoutFile, val onChange: () -> Unit) : View(context) {
    var testing = false
        set(value) {
            field = value
            pressed.clear()
            selected = null
            invalidate()
        }
    var selected: Control? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val pressed = HashMap<Int, Control?>()  // pointer id -> what it holds
    private var dragging: Control? = null
    private var grabX = 0f
    private var grabY = 0f
    private var pinchStart = 0f
    private var sizeStart = 1f
    var safe = RectF()

    private fun unit() = min(width, height) / 10f

    private fun geometry(c: Control): Triple<Float, Float, Float> {
        val w = width.toFloat()
        val h = height.toFloat()
        val d = c.def(w, h, unit())
        return if (c.moved) Triple(c.x * w, c.y * h, d.third * c.size) else d
    }

    private fun at(x: Float, y: Float): Control? = layout.controls.lastOrNull { c ->
        val (cx, cy, r) = geometry(c)
        hypot(x - cx, y - cy) <= r * 1.25f
    }

    // placed by the player from here on; kept inside the screen's safe area
    private fun place(c: Control, x: Float, y: Float) {
        val (_, _, r) = geometry(c)
        c.x = x.coerceIn(safe.left + r, width - safe.right - r) / width
        c.y = y.coerceIn(safe.top + r, height - safe.bottom - r) / height
        c.moved = true
    }

    // the size of the selected control, from the buttons
    fun resize(factor: Float) {
        val c = selected ?: return
        val (x, y) = geometry(c)
        c.x = x / width
        c.y = y / height
        c.size = (c.size * factor).coerceIn(LayoutFile.MIN_SIZE, LayoutFile.MAX_SIZE)
        c.moved = true
        invalidate()
        onChange()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val i = e.actionIndex
        val id = e.getPointerId(i)
        if (testing) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> pressed[id] = at(e.getX(i), e.getY(i))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> pressed.remove(id)
                MotionEvent.ACTION_CANCEL -> pressed.clear()
            }
            onChange()
            invalidate()
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val c = at(e.x, e.y)
                dragging = c
                selected = c
                if (c != null) {
                    val (cx, cy) = geometry(c)
                    grabX = e.x - cx
                    grabY = e.y - cy
                }
                onChange()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2 && dragging != null) {
                pinchStart = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                sizeStart = dragging!!.size
            }
            MotionEvent.ACTION_MOVE -> dragging?.let { c ->
                if (e.pointerCount >= 2 && pinchStart > 0) {
                    val now = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                    c.size = (sizeStart * now / pinchStart).coerceIn(LayoutFile.MIN_SIZE, LayoutFile.MAX_SIZE)
                    c.moved = true
                    if (c.x == 0f && c.y == 0f) {
                        val (x, y) = c.def(width.toFloat(), height.toFloat(), unit())
                        place(c, x, y)
                    }
                } else if (pinchStart == 0f) {
                    place(c, e.x - grabX, e.y - grabY)
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> pinchStart = 0f
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = null
                pinchStart = 0f
            }
        }
        invalidate()
        return true
    }

    fun heldCount() = pressed.values.count { it != null }
    fun held() = pressed.values.filterNotNull().distinct().joinToString(" + ") { it.label }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.rgb(0x20, 0x28, 0x20))
        val a = layout.opacity
        for (c in layout.controls) {
            val (x, y, r) = geometry(c)
            val on = pressed.values.contains(c)
            fill.color = Color.argb(((if (on) 110 else 40) * a).toInt(), 255, 255, 255)
            canvas.drawCircle(x, y, r, fill)
            line.color = if (c === selected) Color.rgb(0xFF, 0xB7, 0x4D) else Color.argb((90 * a).toInt(), 255, 255, 255)
            canvas.drawCircle(x, y, r, line)
            text.color = Color.argb((170 * a).toInt(), 255, 255, 255)
            text.textSize = r * (if (c.label.length > 2) 0.55f else 0.8f)
            canvas.drawText(c.label, x, y + text.textSize * 0.35f, text)
        }
    }
}

// The editor of the on-screen controller, in the launcher so that the layout is set and tried without the game.
class TouchEditorActivity : Activity() {
    private lateinit var file: LayoutFile
    private lateinit var view: EditorView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        file = LayoutFile(this).apply { load() }
        view = EditorView(this, file) { describe() }
        val root = FrameLayout(this)
        root.addView(view)
        // in the middle of the screen, the one place no control is by default
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            setPadding(16, 8, 16, 8)
        }
        fun row() = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            panel.addView(this)
        }
        val bar = row()
        val options = row()
        fun small(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { action() }
            bar.addView(this)
        }
        small("−") { view.resize(0.9f) }
        small("+") { view.resize(1.1f) }
        val test = small(Lang.t("Probar", "Test", "Testar")) {}
        test.setOnClickListener {
            view.testing = !view.testing
            test.text = if (view.testing) Lang.t("Editar", "Edit", "Editar") else Lang.t("Probar", "Test", "Testar")
            describe()
        }
        small(Lang.t("Restablecer", "Reset", "Redefinir")) {
            file.reset()
            view.invalidate()
            sync()
        }
        small(Lang.t("Guardar", "Save", "Salvar")) {
            file.save()
            finish()
        }
        options.addView(TextView(this).apply {
            text = Lang.t("Opacidad", "Opacity", "Opacidade")
            setTextColor(Color.WHITE)
            textSize = 12f
        })
        opacity = SeekBar(this).apply {
            max = 90
            progress = ((file.opacity - 0.1f) * 100).toInt()
            layoutParams = LinearLayout.LayoutParams(220, -2)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar?, value: Int, user: Boolean) {
                    file.opacity = 0.1f + value / 100f
                    view.invalidate()
                }
                override fun onStartTrackingTouch(b: SeekBar?) {}
                override fun onStopTrackingTouch(b: SeekBar?) {}
            })
        }
        options.addView(opacity)
        hide = CheckBox(this).apply {
            text = Lang.t("Ocultar con mando", "Hide with a controller", "Ocultar com controle")
            setTextColor(Color.WHITE)
            textSize = 12f
            isChecked = file.hideWithPad
            setOnCheckedChangeListener { _, on -> file.hideWithPad = on }
        }
        options.addView(hide)
        buzz = CheckBox(this).apply {
            text = Lang.t("Vibración", "Vibration", "Vibração")
            setTextColor(Color.WHITE)
            textSize = 12f
            isChecked = file.vibrate
            setOnCheckedChangeListener { _, on -> file.vibrate = on }
        }
        options.addView(buzz)
        root.addView(panel, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
        }
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {
            topMargin = (resources.displayMetrics.heightPixels * 0.2f).toInt()
        })
        setContentView(root)
        // the screen's cutouts and rounded corners, which no control is dragged under
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 28) {
                insets.displayCutout?.let {
                    view.safe = RectF(it.safeInsetLeft.toFloat(), it.safeInsetTop.toFloat(),
                        it.safeInsetRight.toFloat(), it.safeInsetBottom.toFloat())
                }
            }
            insets
        }
        describe()
    }

    private lateinit var opacity: SeekBar
    private lateinit var hide: CheckBox
    private lateinit var buzz: CheckBox

    // the controls back in step with the file's values, after a reset
    private fun sync() {
        opacity.progress = ((file.opacity - 0.1f) * 100).toInt()
        hide.isChecked = file.hideWithPad
        buzz.isChecked = file.vibrate
        describe()
    }

    private fun describe() {
        status.text = when {
            view.testing && view.heldCount() > 0 -> Lang.t("${view.heldCount()} a la vez: ${view.held()}", "${view.heldCount()} at once: ${view.held()}", "${view.heldCount()} ao mesmo tempo: ${view.held()}")
            view.testing -> Lang.t("Pulsa varios controles a la vez para probarlos", "Press several controls at once to test them", "Toque vários controles ao mesmo tempo para testar")
            view.selected != null -> Lang.t("Arrastra para mover · pellizca o usa − y + para cambiar el tamaño", "Drag to move · pinch or use − and + to resize", "Arraste para mover · faça pinça ou use − e + para redimensionar")
            else -> Lang.t("Toca un control para seleccionarlo", "Tap a control to select it", "Toque em um controle para selecioná-lo")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && Build.VERSION.SDK_INT >= 30) window.insetsController?.hide(WindowInsets.Type.systemBars())
    }
}
