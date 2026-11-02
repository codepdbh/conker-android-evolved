package org.liveandrecompiled.app

// What libprobe.so found (probe.cpp) against what the renderer and the build need. The fallbacks named here are the
// ones in the code: a thing without one that the phone lacks makes it incompatible, so this screen never promises more
// than the runtime does.
object Probe {
    private var loadError: String? = null

    init {
        try {
            System.loadLibrary("probe")
        } catch (e: Throwable) {
            loadError = e.toString()
        }
    }

    @JvmStatic private external fun report(): String

    fun text(): String = loadError?.let { "probe.error=$it\n" } ?: try {
        report()
    } catch (e: Throwable) {
        "probe.error=$e\n"
    }
}

enum class Level { OK, DEGRADED, FAIL }

data class Check(val name: String, val level: Level, val detail: String)

class Compat(report: String) {
    val values: Map<String, String> = report.lineSequence().filter { '=' in it }
        .associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun gpu(key: String) = values["gpu0.$key"]
    private fun on(key: String) = gpu(key) == "1"

    val gpuName: String get() = gpu("name") ?: "desconocida"
    val vulkan: String get() = gpu("api") ?: "ninguno"

    // the API version as a comparable number
    private fun apiAtLeast(major: Int, minor: Int): Boolean {
        val parts = gpu("api")?.split('.')?.mapNotNull { it.toIntOrNull() } ?: return false
        return parts.size >= 2 && (parts[0] > major || (parts[0] == major && parts[1] >= minor))
    }

    val checks: List<Check> by lazy {
        val out = mutableListOf<Check>()
        fun add(name: String, ok: Boolean, fallback: Level, okText: String, noText: String) {
            out += Check(name, if (ok) Level.OK else fallback, if (ok) okText else noText)
        }
        add("CPU ARM64 con NEON", values["cpu.asimd"] == "1", Level.FAIL, "sí", "este procesador no tiene NEON")
        add("Atómicos LSE (ARMv8.1)", values["cpu.atomics"] == "1", Level.DEGRADED, "sí",
            "no: se usan atómicos ARMv8.0, algo más lentos")
        add("Vulkan 1.1 o superior", apiAtLeast(1, 1), Level.FAIL, "Vulkan $vulkan", "el teléfono ofrece Vulkan $vulkan")
        add("Swapchain", on("swapchain"), Level.FAIL, "sí", "no")
        add("Dynamic rendering", on("dynamic_rendering"), Level.DEGRADED, "nativo", "no: render passes clásicos")
        add("Timeline semaphores", on("timeline_semaphore"), Level.DEGRADED, "nativo", "no: se emulan con fences")
        add("Host query reset", on("host_query_reset"), Level.DEGRADED, "nativo", "no: reset en el command buffer")
        add("Extended dynamic state", on("extended_dynamic_state"), Level.DEGRADED, "nativo",
            "no: más pipelines (primera carga más lenta)")
        add("Push descriptors", on("push_descriptor"), Level.DEGRADED, "nativo", "no: descriptor sets por frame")
        add("Geometry shaders", on("geometry_shader"), Level.DEGRADED, "sí", "no: las listas de puntos no se dibujan")
        add("Texturas BC", on("bc"), Level.DEGRADED, "sí", "no: DXT se decodifica al subir (más RAM y carga)")
        add("Formato de profundidad", on("fmt_d24s8") || on("fmt_d32s8"), Level.FAIL, "sí", "ni D24S8 ni D32S8")
        add("Vértices", (gpu("max_vertex_bindings")?.toIntOrNull() ?: 0) >= 16 &&
            (gpu("max_vertex_attributes")?.toIntOrNull() ?: 0) >= 16, Level.FAIL, "16 bindings y atributos",
            "${gpu("max_vertex_bindings")} bindings, ${gpu("max_vertex_attributes")} atributos")
        out
    }

    val verdict: Level get() = when {
        values["vulkan.devices"] == null || values["vulkan.devices"] == "0" -> Level.FAIL
        checks.any { it.level == Level.FAIL } -> Level.FAIL
        checks.any { it.level == Level.DEGRADED } -> Level.DEGRADED
        else -> Level.OK
    }
}
