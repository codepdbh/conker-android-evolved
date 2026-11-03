package org.liveandrecompiled.app

import android.content.Context
import java.io.File
import java.util.Locale

// The game's settings.toml as the launcher changes it, between runs: its own lines only, the rest as the game wrote it.
object GameSettings {
    private fun file(context: Context) = File(context.filesDir, "settings.toml")

    private fun line(key: String) = Regex("(?m)^$key\\s*=\\s*(.*?)\\s*$")

    fun exists(context: Context) = file(context).isFile

    fun get(context: Context, key: String): String? {
        val file = file(context)
        return if (file.isFile) line(key).find(file.readText())?.groupValues?.get(1) else null
    }

    fun set(context: Context, values: Map<String, Any>) {
        val file = file(context)
        var text = if (file.isFile) file.readText() else ""
        for ((key, value) in values) {
            val row = "$key = $value"
            // at the top when new: a TOML table's keys start at its first heading
            text = if (line(key).containsMatchIn(text)) text.replace(line(key), row) else "$row\n$text"
        }
        file.writeText(text)
    }
}

// The app's language, shared with the game's menu (menu_language, ui.cpp), in the order of its texts. The player's choice,
// or the phone's language the first time
object Lang {
    val NAMES = listOf("Español", "English", "Português")
    private const val KEY = "menu_language"
    private var at = 0

    val current get() = at

    fun load(context: Context) {
        at = GameSettings.get(context, KEY)?.toIntOrNull()?.takeIf { it in NAMES.indices } ?: when (Locale.getDefault().language) {
            "es" -> 0
            "pt" -> 2
            else -> 1
        }
        if (GameSettings.get(context, KEY) == null) GameSettings.set(context, mapOf(KEY to at))
    }

    fun choose(context: Context, language: Int) {
        at = language
        GameSettings.set(context, mapOf(KEY to language))
    }

    fun t(es: String, en: String, pt: String) = when (at) {
        1 -> en
        2 -> pt
        else -> es
    }
}

object FrameRate {
    val CHOICES = listOf(30, 60)
    private const val KEY = "frame_rate"

    fun get(context: Context) = GameSettings.get(context, KEY)?.toIntOrNull()?.coerceAtMost(60) ?: 30

    fun set(context: Context, rate: Int) = GameSettings.set(context, mapOf(KEY to rate))
}

// How much the game draws, against how fast. Settings of the game's own menu, together
enum class Quality(val settings: Map<String, Any>) {
    PERFORMANCE(mapOf("render_percent" to 50, "per_pixel_lighting" to false, "fur_distant" to false, "grass_max_lod" to false,
        "grass_wind" to false, "dynamic_water" to false, "floor_depth" to false)),
    BALANCED(mapOf("render_percent" to 75, "per_pixel_lighting" to true, "fur_distant" to false, "grass_max_lod" to false,
        "grass_wind" to false, "dynamic_water" to false, "floor_depth" to true)),
    QUALITY(mapOf("render_percent" to 100, "per_pixel_lighting" to true, "fur_distant" to true, "grass_max_lod" to false,
        "grass_wind" to true, "dynamic_water" to true, "floor_depth" to true));

    fun apply(context: Context) = GameSettings.set(context, settings)

    companion object {
        // the one the settings are, none after changing them one by one in the game's menu
        fun current(context: Context) = entries.firstOrNull { q ->
            q.settings.all { (key, value) -> GameSettings.get(context, key) == value.toString() }
        }
    }
}
