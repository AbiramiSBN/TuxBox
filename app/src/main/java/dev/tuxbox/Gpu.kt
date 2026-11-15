package dev.tuxbox

import java.io.File

/** How OpenGL is provided inside the distro. */
enum class GpuMode(val id: String, val label: String, val help: String, val adrenoOnly: Boolean) {
    OFF("off", "Software", "CPU rendering (llvmpipe). Works everywhere, slowest.", false),
    VIRGL("virgl", "VirGL", "OpenGL forwarded to Android's own GPU driver. Any GPU, and the GPU option for VNC.", false),
    FREEDRENO("freedreno", "Freedreno", "Native Adreno OpenGL + Turnip Vulkan. Fastest. Needs X11 + GPU display (VNC uses VirGL).", true),
    ZINK("zink", "Zink", "OpenGL on Turnip Vulkan. Needs X11 + GPU display (VNC uses VirGL).", true),
    ;
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: OFF }
}

enum class DisplayMode(val id: String, val label: String) {
    TERMUX_X11("x11", "X11 + GPU"),
    VNC("vnc", "VNC (compatible)"),
    ;
    companion object { fun of(id: String?) = entries.firstOrNull { it.id == id } ?: VNC }
}

/**
 * Prebuilt Mesa (Turnip + Freedreno over KGSL + Zink) from
 * github.com/lfdevs/mesa-for-android-container, pinned to one release and verified by sha256.
 */
object MesaDrivers {
    private const val BASE = "https://github.com/lfdevs/mesa-for-android-container/releases/download/" +
        "mesa-27.0.0-devel-20261025-r2/mesa-for-android-container_27.0.0-devel-20261025-r2_"
    const val VERSION = "27.0.0-devel (2026-10-25)"

    fun url(d: Distro) = BASE + d.mesaBuild
    fun fileName(d: Distro) = if (d.mesaBuild.endsWith(".tar")) "mesa.tar" else "mesa.tar.gz"

    /** e.g. "Adreno650v3", or null on non-Adreno devices (Mali, PowerVR, emulators...). */
    fun adrenoModel(): String? = try {
        File("/sys/class/kgsl/kgsl-3d0/gpu_model").readText().trim().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        if (File("/dev/kgsl-3d0").exists()) "Adreno" else null
    }

    fun adrenoSupported() = adrenoModel() != null && android.os.Build.SUPPORTED_ABIS.first() == "arm64-v8a"
}
