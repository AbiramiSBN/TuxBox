package dev.tuxbox

enum class PkgFamily(val id: String) {
    APT("apt"), PACMAN("pacman"), DNF("dnf"), ZYPPER("zypper"), APK("apk"), XBPS("xbps"),
}

data class Distro(
    val id: String,
    val name: String,
    /** Name/release in the linuxcontainers.org image index. */
    val lxcName: String,
    val lxcRelease: String,
    val family: PkgFamily,
    /** Suffix of the matching Adreno Mesa build in lfdevs/mesa-for-android-container, and its sha256. */
    val mesaBuild: String,
    val mesaSha256: String,
    val blurb: String,
)

data class Desktop(
    val id: String,
    val name: String,
    val description: String,
    val size: String,
    val wayland: Boolean = false,
    val unsupportedOn: Set<String> = emptySet(),
    val note: String? = null,
) {
    fun supports(d: Distro) = d.id !in unsupportedOn
}

object Catalog {
    private const val TRIXIE_SHA = "79e1aaf5a49e204fae68a5c87c76248701ba723f4349bda87d310f456c5705cf"

    val distros = listOf(
        Distro("debian", "Debian 13", "debian", "trixie", PkgFamily.APT,
            "debian_trixie_arm64.tar.gz", TRIXIE_SHA, "Stable and well supported. Best first pick."),
        Distro("ubuntu", "Ubuntu 24.04", "ubuntu", "noble", PkgFamily.APT,
            "ubuntu_noble_arm64.tar.gz", "6c456733fe9d3ef7a7a2d6c5519a42d55023f634842c16b48a8b9a817927f846",
            "Popular LTS release with lots of guides online."),
        Distro("kali", "Kali Linux", "kali", "current", PkgFamily.APT,
            "debian_testing_arm64.tar.gz", "25a05d624bb215b656b8446d1b559e36af24e8f7d9c1529445fe97f6f777dc73",
            "Rolling Debian-based distro with security tools (apt install kali-linux-default)."),
        Distro("devuan", "Devuan 6", "devuan", "excalibur", PkgFamily.APT,
            "debian_trixie_arm64.tar.gz", TRIXIE_SHA, "Debian 13 without systemd."),
        Distro("arch", "Arch Linux", "archlinux", "current", PkgFamily.PACMAN,
            "archlinux_arm64.tar", "62c58f5ba71aa8a010adc750870c3ff555f2d58cf7d39723e340a6f5a8d83da9",
            "Rolling release, newest packages."),
        Distro("fedora", "Fedora 45", "fedora", "45", PkgFamily.DNF,
            "fedora_45_arm64.tar.gz", "b98f251b3f03b77ac68e230865bcddc6c35de7aa26a7aac650ea5adf8f13f6f1",
            "Modern and polished, uses dnf."),
        Distro("opensuse", "openSUSE Tumbleweed", "opensuse", "tumbleweed", PkgFamily.ZYPPER,
            "opensuse_tumbleweed_arm64.tar.gz", "c62c13ccc7829d259f864b7172d85a465e02a868fd8b7dfaa6b1b7de99adc9fd",
            "Tested rolling release, uses zypper."),
        Distro("alpine", "Alpine 3.24", "alpine", "3.24", PkgFamily.APK,
            "alpine_3.24_arm64.tar.gz", "452bc884459dc74e8ba7ac72232628be03b0870a5904bafb8a0a14ee154d2fd4",
            "Tiny musl-based distro. Smallest install."),
        Distro("void", "Void Linux", "voidlinux", "current", PkgFamily.XBPS,
            "void_arm64.tar.gz", "e72db7385cebcde133fb0aaa983db17aff0c5417ab4748ab7162fefcfd1c6d0e",
            "Independent rolling distro, uses xbps."),
    )

    /** Distros whose GNOME is 49+, which removed the X11 session we need. */
    private val gnomeWaylandOnly = setOf("arch", "fedora", "opensuse", "alpine", "kali")

    val desktops = listOf(
        Desktop("xfce", "XFCE", "Lightweight, classic and reliable. Best first pick.", "~700 MB"),
        Desktop("lxde", "LXDE", "Ultra-light. Good for TV boxes and older phones.", "~300 MB",
            unsupportedOn = setOf("alpine", "opensuse"), note = "LXDE isn't packaged for this distro."),
        Desktop("lxqt", "LXQt", "Light Qt desktop with a modern look.", "~450 MB"),
        Desktop("mate", "MATE", "Traditional GNOME 2-style desktop.", "~700 MB"),
        Desktop("cinnamon", "Cinnamon", "Polished, Windows-like layout.", "~1 GB",
            unsupportedOn = setOf("alpine"), note = "Cinnamon isn't packaged for Alpine."),
        Desktop("kde", "KDE Plasma", "Feature-rich and beautiful. Needs a strong device.", "~1.5 GB",
            unsupportedOn = setOf("fedora"), note = "Fedora's Plasma is Wayland-only, which can't run here."),
        Desktop("gnome", "GNOME", "GNOME Shell on X11. Heavy.", "~1.5 GB",
            unsupportedOn = gnomeWaylandOnly, note = "This distro ships GNOME 49+, which dropped the X11 session."),
        Desktop("hyprstyle", "Hypr-style", "Hyprland look on X11: bspwm tiling + picom blur, rounded corners and " +
            "animations. Hyprland keybinds (Super+Q terminal, Super+R launcher, Super+C close).", "~250 MB"),
        Desktop("i3", "i3", "Keyboard-driven tiling window manager. Mod = Super.", "~100 MB"),
        Desktop("sway", "Sway", "Wayland tiling compositor (i3-style), streamed with wayvnc. Software rendering only.",
            "~200 MB", wayland = true),
    )

    fun distro(id: String?) = distros.firstOrNull { it.id == id } ?: distros.first()
    fun desktop(id: String?) = desktops.firstOrNull { it.id == id } ?: desktops.first()
}
