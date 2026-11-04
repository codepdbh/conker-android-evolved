package org.liveandrecompiled.app

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

// The game compiled on the phone, from the player's own copy, for players without a PC. The steps of src/apkbuilder/main.cpp
// with what the APK brings: the recompiler (libxbe_recomp.so) and Zig (libzig.so), programs in the native library folder as
// that is the one place Android lets an app run them from, and devkit.zip (the runtime without the game, headers, the NDK's
// sysroot, the config, Zig's library). No game code is in the APK: libmain.so is made here, into the app's own files.
// A build cut short carries on from the parts already made.
object DeviceBuild {
    private const val KIT_ASSET = "devkit.zip"
    private const val FREE_BYTES_NEEDED = 3L shl 30
    // a part takes 300 MB at most; a Redmi Note 8 (3.6 GB) kept 1.2 GB free with 4 at once
    private const val RAM_PER_JOB = 900L shl 20
    private const val MOST_JOBS = 6

    enum class Phase { IDLE, TOOLS, RECOMPILE, COMPILE, LINK, DONE, FAILED }

    class State(val phase: Phase, val done: Int = 0, val total: Int = 0, val error: String = "")

    @Volatile
    var state = State(Phase.IDLE)
        private set
    private val cancelled = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    private fun root(c: Context) = File(c.filesDir, "devbuild")
    private fun kit(c: Context) = File(root(c), "kit")
    private fun work(c: Context) = File(root(c), "work")
    private fun tool(c: Context, name: String) = File(c.applicationInfo.nativeLibraryDir, name)
    private fun logFile(c: Context) = File(File(c.getExternalFilesDir(null), "logs").apply { mkdirs() }, "device-build.log")

    fun library(c: Context) = File(c.filesDir, "game/libmain.so")
    private fun stamp(c: Context) = File(c.filesDir, "game/libmain.sha1")

    // this APK can compile the game: it brings the tools
    fun available(c: Context) = tool(c, "libzig.so").isFile && tool(c, "libxbe_recomp.so").isFile

    // the library made here, for the copy now installed
    fun built(c: Context, copySha: String?): Boolean =
        copySha != null && library(c).isFile && stamp(c).takeIf { it.isFile }?.readText()?.trim() == copySha.trim()

    fun isRunning() = running.get()

    fun enoughRoom(c: Context) = StatFs(c.filesDir.path).availableBytes >= FREE_BYTES_NEEDED

    fun cancel() = cancelled.set(true)

    private fun jobs(c: Context): Int {
        val memory = ActivityManager.MemoryInfo()
        (c.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        val byRam = (memory.totalMem / RAM_PER_JOB).toInt()
        val byCores = (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(1)
        return minOf(byRam, byCores, MOST_JOBS).coerceAtLeast(1)
    }

    private class Failure(message: String) : Exception(message)

    fun run(c: Context, xbe: File, copySha: String) {
        if (!running.compareAndSet(false, true)) return
        cancelled.set(false)
        val log = logFile(c)
        log.writeText("device build, ${Build.MANUFACTURER} ${Build.MODEL}, API ${Build.VERSION.SDK_INT}\n")
        val started = System.currentTimeMillis()
        try {
            unpackKit(c)
            build(c, xbe, copySha, log)
            state = State(Phase.DONE)
            log.appendText("done in ${(System.currentTimeMillis() - started) / 1000} s\n")
            root(c).deleteRecursively()  // about 1 GB the game no longer needs
        } catch (e: Exception) {
            val why = if (cancelled.get()) "cancelled" else e.message ?: e.toString()
            log.appendText("failed after ${(System.currentTimeMillis() - started) / 1000} s: $why\n")
            state = State(Phase.FAILED, error = why)
        } finally {
            running.set(false)
        }
    }

    private fun unpackKit(c: Context) {
        val kit = kit(c)
        val ready = File(kit, ".ready")
        val version = c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode.toString() + File(c.applicationInfo.sourceDir).lastModified()
        if (ready.isFile && ready.readText() == version) return
        state = State(Phase.TOOLS)
        root(c).deleteRecursively()
        kit.mkdirs()
        val base = kit.canonicalPath + File.separator
        ZipInputStream(c.assets.open(KIT_ASSET).buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (cancelled.get()) throw Failure("cancelled")
                val file = File(kit, entry.name)
                if (!file.canonicalPath.startsWith(base)) throw Failure("bad path in the kit: ${entry.name}")
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        ready.writeText(version)
    }

    // a TOML list of strings, as add_xbe_android_kit writes it
    private fun list(toml: String, key: String): List<String> {
        val body = Regex("(?m)^$key = \\[(.*)]\\s*$").find(toml)?.groupValues?.get(1) ?: return emptyList()
        return Regex("\"([^\"]*)\"").findAll(body).map { it.groupValues[1] }.toList()
    }

    private fun build(c: Context, xbe: File, copySha: String, log: File) {
        val kit = kit(c)
        val work = work(c)
        val gen = File(work, "generated").apply { mkdirs() }
        val obj = File(work, "obj").apply { mkdirs() }
        val cache = File(work, "cache").apply { mkdirs() }
        val libc = File(work, "libc.txt")
        val sysroot = File(kit, "sysroot/usr")
        val toml = File(kit, "android.toml").readText()
        val api = Regex("(?m)^api = (\\d+)").find(toml)?.groupValues?.get(1) ?: "28"
        libc.writeText("include_dir=$sysroot/include\nsys_include_dir=$sysroot/include/aarch64-linux-android\n" +
            "crt_dir=$sysroot/lib/aarch64-linux-android/$api\nmsvc_lib_dir=\nkernel32_lib_dir=\ngcc_dir=\n")
        val zig = tool(c, "libzig.so").path
        fun process(args: List<String>, output: File): Process = ProcessBuilder(args).directory(kit)
            .redirectErrorStream(true).redirectOutput(output).apply {
                environment().apply {
                    put("ZIG_LIBC", libc.path)
                    put("ZIG_LIB_DIR", File(kit, "zig-lib").path)
                    put("ZIG_GLOBAL_CACHE_DIR", cache.path)
                    put("ZIG_LOCAL_CACHE_DIR", cache.path)
                    put("HOME", work.path)
                    put("TMPDIR", work.path)
                }
            }.start()
        val active = java.util.Collections.synchronizedSet(HashSet<Process>())
        fun waitFor(p: Process): Int {
            active.add(p)
            try {
                while (!p.waitFor(500, TimeUnit.MILLISECONDS)) {
                    if (cancelled.get()) p.destroyForcibly()
                }
                return p.exitValue()
            } finally {
                active.remove(p)
            }
        }

        // 1. the game's code to C++, the once
        val recompiled = File(work, ".recompiled")
        if (!recompiled.isFile) {
            state = State(Phase.RECOMPILE)
            val out = File(work, "recompile.log")
            val code = waitFor(process(listOf(tool(c, "libxbe_recomp.so").path, "config/game.toml", xbe.path, gen.path,
                "--xdk", "xdk"), out))
            log.appendText(out.readText().takeLast(2000))
            if (code != 0) throw Failure("recompile: exit $code")
            // the runtime in the kit was built against these addresses; another set would call the wrong functions
            if (!File(gen, "image_symbols.h").readBytes().contentEquals(File(kit, "include/image_symbols.h").readBytes())) {
                throw Failure("the converted code doesn't match this version's runtime")
            }
            recompiled.writeText("")
        }

        // 2. every part on its own, skipping those a build cut short had made
        val sources = gen.listFiles { f -> f.extension == "cpp" }.orEmpty().sortedBy { it.name }
        val cxx = list(toml, "cxxflags")
        val done = AtomicInteger(sources.count { File(obj, it.nameWithoutExtension + ".o").isFile })
        state = State(Phase.COMPILE, done.get(), sources.size)
        val count = jobs(c)
        log.appendText("compiling ${sources.size} parts, $count at a time\n")
        val failure = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val pool = Executors.newFixedThreadPool(count)
        for (source in sources) {
            val made = File(obj, source.nameWithoutExtension + ".o")
            if (made.isFile) continue
            pool.execute {
                if (failure.get() != null || cancelled.get()) return@execute
                val partial = File(obj, source.nameWithoutExtension + ".part")
                val out = File(obj, source.nameWithoutExtension + ".log")
                val code = waitFor(process(listOf(zig, "c++") + cxx + listOf("-Iinclude", "-I${gen.path}", "-c", source.path, "-o", partial.path), out))
                if (code == 0 && partial.renameTo(made)) {
                    out.delete()
                    state = State(Phase.COMPILE, done.incrementAndGet(), sources.size)
                } else if (!cancelled.get()) {
                    failure.compareAndSet(null, "${source.name}: exit $code\n" + out.readText().take(1500))
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(1, TimeUnit.DAYS)
        if (cancelled.get()) throw Failure("cancelled")
        failure.get()?.let { throw Failure(it) }

        // 3. link, in the order the project's own build does (the mapping, then the parts by number)
        state = State(Phase.LINK)
        val rank = { f: File -> f.nameWithoutExtension.substringAfterLast('.', "").toIntOrNull() ?: -1 }
        val objects = sources.map { File(obj, it.nameWithoutExtension + ".o") }.sortedBy(rank)
        val so = File(work, "libmain.so")
        val rsp = File(work, "link.rsp")
        val ld = list(toml, "ldflags").map { it.replace("{kit}", kit.path) }
        rsp.writeText((objects.map { it.path } + ld + list(toml, "libs") + listOf("-o", so.path)).joinToString("\n") { "\"$it\"" })
        val linkLog = File(work, "link.log")
        val code = waitFor(process(listOf(zig, "c++", "@${rsp.path}"), linkLog))
        log.appendText(linkLog.readText().takeLast(2000))
        if (code != 0 || !so.isFile) throw Failure("link: exit $code\n" + linkLog.readText().takeLast(1500))

        library(c).parentFile?.mkdirs()
        if (!so.renameTo(library(c))) so.copyTo(library(c), overwrite = true)
        library(c).setReadOnly()
        stamp(c).writeText(copySha)
    }
}

// The build goes on in the foreground, with its notification: without one Android stops it once the player leaves the launcher.
class DeviceBuildService : Service() {
    private lateinit var lock: PowerManager.WakeLock

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String, done: Int, total: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, SetupActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name)).setContentText(text).setContentIntent(open).setOngoing(true)
            .setProgress(total, done, total == 0).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Lang.load(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, Lang.t("Preparar el juego", "Preparing the game", "Preparar o jogo"), NotificationManager.IMPORTANCE_LOW))
        val first = notification(Lang.t("Preparando el juego…", "Preparing the game…", "Preparando o jogo…"), 0, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, first)
        }
        val xbe = File(filesDir, "assets/default.xbe")
        val sha = File(filesDir, "assets/.ready").takeIf { it.isFile }?.readText()?.trim()
        if (sha == null || !xbe.isFile) {
            stopSelf()
            return START_NOT_STICKY
        }
        lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "conker:devicebuild")
        lock.acquire(TimeUnit.HOURS.toMillis(6))
        val worker = thread(name = "device-build") { DeviceBuild.run(this, xbe, sha) }
        thread(name = "device-build-progress") {
            while (worker.isAlive) {
                val s = DeviceBuild.state
                manager.notify(ID, notification(phaseText(s), s.done, s.total))
                Thread.sleep(2000)
            }
            if (lock.isHeld) lock.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "device-build"
        private const val ID = 1

        fun phaseText(s: DeviceBuild.State) = when (s.phase) {
            DeviceBuild.Phase.TOOLS -> Lang.t("Preparando las herramientas…", "Preparing the tools…", "Preparando as ferramentas…")
            DeviceBuild.Phase.RECOMPILE -> Lang.t("Convirtiendo el código del juego…", "Converting the game's code…", "Convertendo o código do jogo…")
            DeviceBuild.Phase.COMPILE -> Lang.t("Compilando: ${s.done} de ${s.total}", "Compiling: ${s.done} of ${s.total}", "Compilando: ${s.done} de ${s.total}")
            DeviceBuild.Phase.LINK -> Lang.t("Terminando: esto puede tardar varios minutos…", "Finishing: this can take several minutes…", "Terminando: pode levar vários minutos…")
            DeviceBuild.Phase.DONE -> Lang.t("Juego listo.", "Game ready.", "Jogo pronto.")
            DeviceBuild.Phase.FAILED -> Lang.t("No se pudo preparar el juego.", "The game couldn't be prepared.", "Não foi possível preparar o jogo.")
            DeviceBuild.Phase.IDLE -> ""
        }
    }
}
