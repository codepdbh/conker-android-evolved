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
        add(Lang.t("CPU ARM64 con NEON", "ARM64 CPU with NEON", "CPU ARM64 com NEON"), values["cpu.asimd"] == "1", Level.FAIL, Lang.t("sí", "yes", "sim"), Lang.t("este procesador no tiene NEON", "this processor has no NEON", "este processador não tem NEON"))
        add(Lang.t("Atómicos LSE (ARMv8.1)", "LSE atomics (ARMv8.1)", "Atômicos LSE (ARMv8.1)"), values["cpu.atomics"] == "1", Level.DEGRADED, Lang.t("sí", "yes", "sim"),
            Lang.t("no: se usan atómicos ARMv8.0, algo más lentos", "no: ARMv8.0 atomics are used, a little slower", "não: usa atômicos ARMv8.0, um pouco mais lentos"))
        add(Lang.t("Vulkan 1.1 o superior", "Vulkan 1.1 or later", "Vulkan 1.1 ou superior"), apiAtLeast(1, 1), Level.FAIL, "Vulkan $vulkan", Lang.t("el teléfono ofrece Vulkan $vulkan", "the phone offers Vulkan $vulkan", "o telefone oferece Vulkan $vulkan"))
        add("Swapchain", on("swapchain"), Level.FAIL, Lang.t("sí", "yes", "sim"), Lang.t("no", "no", "não"))
        add("Dynamic rendering", on("dynamic_rendering"), Level.DEGRADED, Lang.t("nativo", "native", "nativo"), Lang.t("no: render passes clásicos", "no: classic render passes", "não: render passes clássicos"))
        add("Timeline semaphores", on("timeline_semaphore"), Level.DEGRADED, Lang.t("nativo", "native", "nativo"), Lang.t("no: se emulan con fences", "no: emulated with fences", "não: emulados com fences"))
        add("Host query reset", on("host_query_reset"), Level.DEGRADED, Lang.t("nativo", "native", "nativo"), Lang.t("no: reset en el command buffer", "no: reset in the command buffer", "não: reset no command buffer"))
        add("Extended dynamic state", on("extended_dynamic_state"), Level.DEGRADED, Lang.t("nativo", "native", "nativo"),
            Lang.t("no: más pipelines (primera carga más lenta)", "no: more pipelines (slower first load)", "não: mais pipelines (primeira carga mais lenta)"))
        add("Push descriptors", on("push_descriptor"), Level.DEGRADED, Lang.t("nativo", "native", "nativo"), Lang.t("no: descriptor sets por frame", "no: descriptor sets per frame", "não: descriptor sets por quadro"))
        add("Geometry shaders", on("geometry_shader"), Level.DEGRADED, Lang.t("sí", "yes", "sim"), Lang.t("no: las listas de puntos no se dibujan", "no: point lists aren't drawn", "não: listas de pontos não são desenhadas"))
        add(Lang.t("Texturas BC", "BC textures", "Texturas BC"), on("bc"), Level.DEGRADED, Lang.t("sí", "yes", "sim"), Lang.t("no: DXT se decodifica al subir (más RAM y carga)", "no: DXT is decoded on upload (more RAM and load)", "não: DXT é decodificado no envio (mais RAM e carga)"))
        add(Lang.t("Formato de profundidad", "Depth format", "Formato de profundidade"), on("fmt_d24s8") || on("fmt_d32s8"), Level.FAIL, Lang.t("sí", "yes", "sim"), Lang.t("ni D24S8 ni D32S8", "neither D24S8 nor D32S8", "nem D24S8 nem D32S8"))
        add(Lang.t("Vértices", "Vertices", "Vértices"), (gpu("max_vertex_bindings")?.toIntOrNull() ?: 0) >= 16 &&
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
