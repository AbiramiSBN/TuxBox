package dev.tuxbox

import android.content.Context
import android.os.Build
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Paths, rootfs installation and proot command construction. */
class LinuxEnv(private val ctx: Context) {
    private val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
    private val proot = File(nativeDir, "libproot.so")
    private val tmpDir = File(ctx.cacheDir, "proot-tmp")
    private val fakeProc = File(ctx.filesDir, "fakeproc")
    private val emptyDri = File(ctx.filesDir, "empty-dri").apply { mkdirs() }
    private val shared = ctx.getExternalFilesDir(null) ?: File(ctx.filesDir, "shared")

    fun rootfs(d: Distro) = File(ctx.filesDir, "distros/${d.id}")
    fun isRootfsInstalled(d: Distro) = File(rootfs(d), ".tuxbox-rootfs").exists()
    fun installedDesktops(d: Distro): Set<String> =
        File(rootfs(d), "root/.tuxbox").list()
            ?.filter { it.startsWith("de-") }?.map { it.removePrefix("de-") }?.toSet()
            ?: emptySet()

    fun gpuDriversInstalled(d: Distro) = File(rootfs(d), "root/.tuxbox/gpu-drivers").exists()

    fun prootAvailable() = proot.exists()

    private val lxcArch: String
        get() = when (Build.SUPPORTED_ABIS.first()) {
            "arm64-v8a" -> "arm64"
            "armeabi-v7a" -> "armhf"
            "x86_64" -> "amd64"
            else -> throw IOException("Unsupported CPU ABI: ${Build.SUPPORTED_ABIS.first()}")
        }

    // ---------------------------------------------------------------- rootfs

    fun installRootfs(d: Distro, log: (String) -> Unit) {
        val index = URL("$LXC/meta/1.0/index-system").readText()
        val prefix = "${d.lxcName};${d.lxcRelease};$lxcArch;default;"
        val line = index.lineSequence().lastOrNull { it.startsWith(prefix) }
            ?: throw IOException("No ${d.name} image for $lxcArch on linuxcontainers.org")
        val url = LXC + line.split(';')[5] + "rootfs.tar.xz"

        val archive = File(ctx.cacheDir, "${d.id}-rootfs.tar.xz")
        log("Downloading $url")
        download(url, archive, log)

        val dest = rootfs(d)
        if (dest.exists()) deleteTree(dest)
        dest.mkdirs()
        log("Extracting root filesystem (this takes a few minutes)...")
        extract(archive, dest, log)
        archive.delete()

        applyFixes(dest)
        File(dest, ".tuxbox-rootfs").writeText(url)
        log("${d.name} root filesystem ready.")
    }

    fun download(url: String, out: File, log: (String) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for $url")
        val total = conn.contentLengthLong
        var done = 0L
        var lastPct = -1
        conn.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct / 5 != lastPct / 5) {
                            lastPct = pct
                            log("  $pct%  (${done / 1_048_576} / ${total / 1_048_576} MB)")
                        }
                    }
                }
            }
        }
    }

    /** Downloads the distro's Adreno Mesa build into its /tmp and checks the pinned sha256. */
    fun downloadMesa(d: Distro, log: (String) -> Unit): String {
        val name = MesaDrivers.fileName(d)
        val out = File(rootfs(d), "tmp/$name")
        log("Downloading Mesa ${MesaDrivers.VERSION} (Turnip + Freedreno + Zink)")
        download(MesaDrivers.url(d), out, log)
        val digest = MessageDigest.getInstance("SHA-256")
        out.inputStream().use { i ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = i.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (hex != d.mesaSha256) {
            out.delete()
            throw IOException("Checksum mismatch for GPU drivers (got $hex). Download refused.")
        }
        log("  checksum OK")
        return "/tmp/$name"
    }

    private fun extract(archive: File, dest: File, log: (String) -> Unit) {
        val destPath = dest.path + "/"
        val hardLinks = mutableListOf<Triple<File, String, Int>>()
        var count = 0
        TarArchiveInputStream(XZCompressorInputStream(BufferedInputStream(archive.inputStream(), 1 shl 16))).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                val name = e.name.removePrefix("./").trimEnd('/')
                if (name.isEmpty()) continue
                if (name.split('/').any { it == ".." }) continue
                val f = File(dest, name)
                if (!f.path.startsWith(destPath)) continue
                f.parentFile?.mkdirs()
                when {
                    e.isDirectory -> { f.mkdirs(); chmod(f, (e.mode and 0x1FF) or 0x1C0) }
                    e.isSymbolicLink -> { f.delete(); Os.symlink(e.linkName, f.path) }
                    e.isLink -> hardLinks += Triple(f, e.linkName.removePrefix("./"), e.mode and 0x1FF)
                    e.isFile -> {
                        f.delete()
                        f.outputStream().use { tar.copyTo(it) }
                        chmod(f, (e.mode and 0x1FF) or 0x180)
                    }
                    else -> Unit // device nodes, fifos: provided by bind mounts
                }
                if (++count % 5000 == 0) log("  $count files...")
            }
        }
        // Android forbids hard links in app storage, so copy instead.
        for ((f, target, mode) in hardLinks) {
            val src = File(dest, target)
            if (src.isFile) { src.copyTo(f, overwrite = true); chmod(f, mode or 0x180) }
        }
        log("  extracted $count entries")
    }

    private fun chmod(f: File, mode: Int) = try { Os.chmod(f.path, mode) } catch (_: Exception) {}

    private fun applyFixes(root: File) {
        File(root, "etc/resolv.conf").apply { delete(); writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n") }
        File(root, "etc/hosts").apply { delete(); writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost\n") }
        File(root, "tmp").apply { mkdirs(); chmod(this, 0x3FF) } // 01777
        File(root, "root/.tuxbox").mkdirs()
    }

    private fun tryDelete(p: Path) = try { Files.deleteIfExists(p) } catch (_: IOException) { false }

    /** Deletes a tree WITHOUT following symlinks (rootfs contains links to absolute host paths). */
    fun deleteTree(dir: File) {
        if (!dir.exists()) return
        Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(d: Path, a: BasicFileAttributes): FileVisitResult {
                chmod(d.toFile(), 0x1FF); return FileVisitResult.CONTINUE
            }
            override fun visitFile(f: Path, a: BasicFileAttributes): FileVisitResult {
                tryDelete(f); return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(f: Path, e: IOException): FileVisitResult {
                tryDelete(f); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(d: Path, e: IOException?): FileVisitResult {
                tryDelete(d); return FileVisitResult.CONTINUE
            }
        })
    }

    // ----------------------------------------------------------------- proot

    /** Copies assets/tuxbox (scripts + theme) into the distro's /opt/tuxbox after each app update. */
    private fun syncScripts(root: File) {
        val dir = File(root, "opt/tuxbox")
        val stamp = File(dir, ".stamp")
        val version = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime.toString()
        if (stamp.exists() && stamp.readText() == version) return
        copyAssets("tuxbox", dir)
        stamp.writeText(version)
    }

    private fun copyAssets(path: String, dest: File) {
        val children = ctx.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            ctx.assets.open(path).use { i -> dest.outputStream().use { i.copyTo(it) } }
            chmod(dest, 0x1ED)
            return
        }
        dest.mkdirs()
        for (c in children) copyAssets("$path/$c", File(dest, c))
    }

    private fun prepareFakeProc(): List<String> {
        fakeProc.mkdirs()
        val fakes = mapOf(
            "loadavg" to "0.12 0.07 0.02 2/165 765\n",
            "uptime" to "1234.56 4321.00\n",
            "version" to "Linux version 6.17.0-tuxbox (tuxbox@android) #1 SMP PREEMPT\n",
            "stat" to "cpu  1957 0 2877 93280 262 342 254 87 0 0\ncpu0 31 0 226 12027 82 10 4 9 0 0\n" +
                "intr 0\nctxt 0\nbtime 0\nprocesses 0\nprocs_running 1\nprocs_blocked 0\n",
            "vmstat" to "nr_free_pages 146031\n",
        )
        val binds = mutableListOf<String>()
        for ((name, content) in fakes) {
            if (File("/proc/$name").canRead()) continue
            val f = File(fakeProc, name)
            if (!f.exists()) f.writeText(content)
            binds += "--bind=${f.path}:/proc/$name"
        }
        return binds
    }

    /** Builds a ProcessBuilder running [command] inside the distro via proot. */
    fun prootProcess(d: Distro, vararg command: String, env: Map<String, String> = emptyMap()): ProcessBuilder {
        val root = rootfs(d)
        syncScripts(root)
        tmpDir.mkdirs()
        shared.mkdirs()
        File(root, "tmp").mkdirs()

        val args = mutableListOf(
            proot.path,
            "--kill-on-exit",
            "--link2symlink",
            "--root-id",
            "--sysvipc",
            "--kernel-release=6.17.0-tuxbox",
            "--rootfs=${root.path}",
            "--bind=/dev",
            // Hide Android's DRM nodes: Xvnc tries DRI3 on renderD128 and hangs with the
            // Adreno Mesa build. Our GPU paths (Turnip/Freedreno) use /dev/kgsl-3d0 instead.
            "--bind=${emptyDri.path}:/dev/dri",
            "--bind=/proc",
            "--bind=/sys",
            "--bind=/dev/urandom:/dev/random",
            "--bind=/proc/self/fd:/dev/fd",
            "--bind=/proc/self/fd/0:/dev/stdin",
            "--bind=/proc/self/fd/1:/dev/stdout",
            "--bind=/proc/self/fd/2:/dev/stderr",
            "--bind=${root.path}/tmp:/dev/shm",
            "--bind=${shared.path}:/root/android",
            "--cwd=/root",
        )
        args += prepareFakeProc()
        args += listOf(
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "TERM=xterm-256color", "LANG=C.UTF-8", "TMPDIR=/tmp",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )
        args += env.map { (k, v) -> "$k=$v" }
        args += command

        return ProcessBuilder(args).redirectErrorStream(true).apply {
            environment().apply {
                clear()
                put("LD_LIBRARY_PATH", nativeDir.path)
                put("PROOT_LOADER", File(nativeDir, "libproot-loader.so").path)
                File(nativeDir, "libproot-loader32.so").takeIf { it.exists() }
                    ?.let { put("PROOT_LOADER_32", it.path) }
                put("PROOT_TMP_DIR", tmpDir.path)
                put("HOME", ctx.filesDir.path)
                put("PATH", "/system/bin")
            }
        }
    }

    // ----------------------------------------------------------------- VirGL

    fun virglAvailable() = File(nativeDir, "libvirgl_test_server.so").exists()

    /**
     * Starts virglrenderer's vtest server: it executes OpenGL from the distro's Mesa
     * (GALLIUM_DRIVER=virpipe) on Android's own GLES driver, so any GPU works.
     */
    fun virglServer(d: Distro): ProcessBuilder {
        val sock = File(rootfs(d), "tmp/.virgl_test").apply { delete() }
        return ProcessBuilder(File(nativeDir, "libvirgl_test_server.so").path, "--socket-path", sock.path)
            .redirectErrorStream(true).apply {
                environment()["LD_LIBRARY_PATH"] = nativeDir.path
                environment()["TMPDIR"] = tmpDir.apply { mkdirs() }.path
            }
    }

    // ------------------------------------------------------------ Termux:X11

    /**
     * Starts the X server built into TuxBox (Termux:X11's "lorie", embedded as a library)
     * on display :0, the same way the `termux-x11` command does. Its socket lands in the
     * distro's /tmp, and TuxBox's own display activity connects to it.
     */
    fun x11Server(d: Distro): ProcessBuilder {
        val root = rootfs(d)
        val tmp = File(root, "tmp")
        File(tmp, ".X11-unix").apply { mkdirs(); chmod(this, 0x3FF) }
        File(tmp, ".X11-unix/X0").delete()
        File(tmp, ".X0-lock").delete()
        return ProcessBuilder(
            "/system/bin/app_process", "-Xnoimage-dex2oat", "/",
            "--nice-name=tuxbox-x11", "com.termux.x11.CmdEntryPoint", ":0",
        ).redirectErrorStream(true).apply {
            environment().apply {
                remove("LD_PRELOAD")
                put("CLASSPATH", ctx.applicationInfo.sourceDir)
                put("LORIE_NATIVE_LIB_DIR", nativeDir.path)
                put("LD_LIBRARY_PATH", nativeDir.path)
                put("TMPDIR", tmp.path)
                put("XKB_CONFIG_ROOT", File(root, "usr/share/X11/xkb").path)
            }
        }
    }

    companion object {
        const val X11_ACTIVITY = "com.termux.x11.MainActivity"
        const val LXC = "https://images.linuxcontainers.org"
    }
}
