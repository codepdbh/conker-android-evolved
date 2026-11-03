package org.liveandrecompiled.app

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.system.Os
import android.os.Vibrator
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import org.libsdl.app.SDLActivity
import java.io.File

// SDL's activity, running the game in libmain.so (SDL_main, src/runtime/app/main_sdl.cpp), over the whole screen
class GameActivity : SDLActivity() {
    // SDL is linked into libmain.so, there is no libSDL3.so
    override fun getLibraries(): Array<String> = arrayOf("main")

    private val sentinel by lazy { File(filesDir, RUNNING) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // left behind by a run that dies: the launcher reads it to offer the report (a run that ends well removes it)
        sentinel.writeText(System.currentTimeMillis().toString())
        applyDebugEnvironment()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideBars()
    }

    // a touch control pressed (touch.cpp); from any thread, a phone without a vibrator does nothing
    @Suppress("unused")
    fun buzz() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun onDestroy() {
        if (isFinishing) sentinel.delete()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    // the runtime's XBE_ switches for a phone, where nothing can set them: one NAME=value a line in files/debug_env.txt
    private fun applyDebugEnvironment() {
        val file = File(filesDir, DEBUG_ENV)
        if (!file.exists()) return
        file.readLines().map { it.trim() }.filter { it.startsWith("XBE_") && '=' in it }.forEach {
            Os.setenv(it.substringBefore('='), it.substringAfter('='), true)
        }
    }

    // the status and navigation bars away until swiped in
    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    companion object {
        const val RUNNING = ".game-running"
        const val DEBUG_ENV = "debug_env.txt"
    }
}
