package dev.tuxbox

import android.content.Context
import android.content.Intent
import android.system.Os
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter

sealed interface SessionState {
    data object Idle : SessionState
    data class Busy(val task: String) : SessionState
    data class Running(val distro: Distro, val desktop: Desktop) : SessionState
}

/** Single place that owns the Linux processes. Lives as long as the app process. */
object SessionManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lines = ArrayDeque<String>()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state
    /** Bumped whenever on-disk install state may have changed. */
    val revision = MutableStateFlow(0)

    @Volatile private var process: Process? = null
    @Volatile private var logFile: PrintWriter? = null

    fun log(line: String) = synchronized(lines) {
        // apt/pacman progress uses \r; keep the latest segment
        val l = line.substringAfterLast('\r')
        lines.addLast(l)
        while (lines.size > 1500) lines.removeFirst()
        _log.value = lines.toList()
        logFile?.println(l)
    }

    fun clearLog() = synchronized(lines) { lines.clear(); _log.value = emptyList() }

    /**
     * Kills every process running under our app's uid except the app itself:
     * proot, everything inside the distro (including daemons that detached), the
     * X server and VirGL. Destroying only the proot process is not enough.
     */
    fun killAllLinux(): Int {
        val me = android.os.Process.myPid()
        val uid = android.os.Process.myUid()
        var n = 0
        File("/proc").listFiles()?.forEach { f ->
            val pid = f.name.toIntOrNull() ?: return@forEach
            if (pid == me) return@forEach
            try {
                if (Os.stat(f.path).st_uid == uid) { android.os.Process.sendSignal(pid, 9); n++ }
            } catch (_: Exception) {}
        }
        return n
    }

    private fun launch(ctx: Context, state: SessionState, block: suspend (LinuxEnv) -> Unit) {
        if (_state.value != SessionState.Idle) { log("Busy — stop the current task first."); return }
        val app = ctx.applicationContext
        _state.value = state
        ContextCompat.startForegroundService(app, Intent(app, LinuxService::class.java))
        scope.launch {
            try {
                logFile = PrintWriter(File(app.getExternalFilesDir(null) ?: app.filesDir, "last-session.log").bufferedWriter(), true)
                if (killAllLinux() > 0) { log("Cleaned up leftover Linux processes."); Thread.sleep(300) }
                block(LinuxEnv(app))
            } catch (e: Exception) {
                log("ERROR: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                killAllLinux()
                process = null
                logFile?.close(); logFile = null
                _state.value = SessionState.Idle
                revision.value++
            }
        }
    }

    private fun run(pb: ProcessBuilder): Int {
        val p = pb.start()
        process = p
        p.inputStream.bufferedReader().useLines { seq -> seq.forEach { log(it) } }
        return p.waitFor()
    }

    /** Starts a helper process whose output goes to the log with a prefix. */
    private fun helper(pb: ProcessBuilder, tag: String) {
        val p = pb.start()
        Thread { p.inputStream.bufferedReader().useLines { seq -> seq.forEach { log("[$tag] $it") } } }.start()
    }

    private fun checkProot(env: LinuxEnv) {
        check(env.prootAvailable()) {
            "proot binary missing from the APK. Run scripts/fetch-proot.sh before building."
        }
    }

    private fun bootstrap(env: LinuxEnv, distro: Distro) {
        // Alpine ships without bash, which our scripts need.
        if (distro.family == PkgFamily.APK && !File(env.rootfs(distro), "bin/bash").exists()) {
            log("Installing bash...")
            run(env.prootProcess(distro, "/bin/sh", "-c", "apk update && apk add bash"))
        }
    }

    fun install(ctx: Context, distro: Distro, desktop: Desktop) =
        launch(ctx, SessionState.Busy("Installing ${desktop.name} on ${distro.name}")) { env ->
            checkProot(env)
            if (!env.isRootfsInstalled(distro)) env.installRootfs(distro, ::log)
            revision.value++
            bootstrap(env, distro)
            log("Installing ${desktop.name}. Large desktops can take 10-30 minutes.")
            val code = run(env.prootProcess(distro, "/bin/bash", "/opt/tuxbox/install.sh", distro.family.id, desktop.id))
            log(if (code == 0) "✔ ${desktop.name} is ready." else "✘ Install failed (exit $code). Check the log above, then retry.")
        }

    data class Options(val width: Int, val height: Int, val display: DisplayMode, val gpu: GpuMode, val extraEnv: String)

    /** Parses "KEY=value" lines/words typed by the user. */
    private fun parseEnv(text: String): Map<String, String> =
        text.split('\n', ' ').map { it.trim() }.filter { '=' in it && !it.startsWith("#") }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
            .filterKeys { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }

    fun start(ctx: Context, distro: Distro, desktop: Desktop, o: Options) =
        launch(ctx, SessionState.Running(distro, desktop)) { env ->
            checkProot(env)
            val display = if (desktop.wayland) DisplayMode.VNC else o.display
            if (desktop.wayland && o.display == DisplayMode.TERMUX_X11)
                log("${desktop.name} is a Wayland compositor; using the built-in viewer.")
            var gpu = o.gpu
            if (display == DisplayMode.VNC && gpu.adrenoOnly) {
                // Xvnc has no DRI3, so Freedreno/Zink silently fall back to llvmpipe there.
                gpu = if (env.virglAvailable()) GpuMode.VIRGL else GpuMode.OFF
                log("${o.gpu.label} needs the X11 + GPU display; using ${gpu.label} with VNC.")
            }
            if (gpu == GpuMode.VIRGL) {
                if (env.virglAvailable()) { log("Starting VirGL server..."); helper(env.virglServer(distro), "virgl") }
                else { log("VirGL isn't available for this CPU; using software rendering."); gpu = GpuMode.OFF }
            }
            if (display == DisplayMode.TERMUX_X11) {
                log("Starting X server...")
                helper(env.x11Server(distro), "x11")
            }
            log("Starting ${desktop.name} at ${o.width}x${o.height}...")
            val sessionEnv = mapOf("TUXBOX_DISPLAY" to display.id, "TUXBOX_GPU" to gpu.id) + parseEnv(o.extraEnv)
            val code = run(env.prootProcess(
                distro, "/bin/bash", "/opt/tuxbox/start.sh", desktop.id, "${o.width}", "${o.height}", env = sessionEnv,
            ))
            log("Session exited (code $code).")
        }

    fun installGpu(ctx: Context, distro: Distro) =
        launch(ctx, SessionState.Busy("Installing GPU drivers on ${distro.name}")) { env ->
            checkProot(env)
            check(env.isRootfsInstalled(distro)) { "Install a desktop on ${distro.name} first." }
            bootstrap(env, distro)
            val archive = env.downloadMesa(distro, ::log)
            val code = run(env.prootProcess(distro, "/bin/bash", "/opt/tuxbox/gpu.sh", distro.family.id, archive))
            log(if (code == 0) "✔ GPU drivers ready." else "✘ GPU driver install failed (exit $code).")
        }

    fun shell(ctx: Context, distro: Distro, command: String) =
        launch(ctx, SessionState.Busy("Running command")) { env ->
            checkProot(env)
            log("# $command")
            val code = run(env.prootProcess(distro, "/bin/sh", "-lc", command))
            log("(exit $code)")
        }

    fun reset(ctx: Context, distro: Distro) =
        launch(ctx, SessionState.Busy("Removing ${distro.name}")) { env ->
            env.deleteTree(env.rootfs(distro))
            log("${distro.name} removed.")
        }

    fun stop() {
        if (_state.value == SessionState.Idle) return
        log("Stopping...")
        Thread { killAllLinux() }.start()
    }
}
