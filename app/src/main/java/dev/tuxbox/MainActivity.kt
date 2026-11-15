package dev.tuxbox

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        // Defaults for the embedded X11 display (Termux:X11 prefs): a desktop shouldn't sleep after 1 minute.
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(this).let { p ->
            if (!p.contains("screenIdleTimeout")) p.edit().putString("screenIdleTimeout", "never").apply()
            // Back button shows/hides the key bar at the bottom of the X11 desktop.
            if (!p.contains("backButtonAction")) p.edit().putString("backButtonAction", "toggle additional key bar").apply()
            // Tap where your finger is (not trackpad-style); drag = mouse drag.
            if (!p.contains("touchMode")) p.edit().putString("touchMode", "2").apply()
        }
        setContent { LauncherTheme { Launcher() } }
    }
}

// ----------------------------------------------------------------- theme

private val Ink = Color(0xFF0B0D10)
private val Panel = Color(0xCC161A20)
private val PanelHi = Color(0xFF232933)
private val TextDim = Color(0xFF9AA3AE)
private val Accent = Color(0xFF8AB4F8)

@Composable
fun LauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, onPrimary = Ink, surface = Color(0xFF161A20), background = Ink,
            surfaceVariant = PanelHi, onSurface = Color(0xFFE8EAED), onSurfaceVariant = TextDim,
        ),
        content = content,
    )
}

private fun defaultResolution(ctx: Context): Pair<Int, Int> {
    // Full physical screen (displayMetrics leaves out the navigation bar, which skews the aspect ratio).
    val m = android.util.DisplayMetrics()
    @Suppress("DEPRECATION")
    ctx.getSystemService(android.view.WindowManager::class.java).defaultDisplay.getRealMetrics(m)
    val long = maxOf(m.widthPixels, m.heightPixels).toFloat()
    val short = minOf(m.widthPixels, m.heightPixels).toFloat()
    val h = 720
    val w = ((long / short * h) / 8).roundToInt() * 8
    return w to h
}

private enum class Dialog { NONE, DISTRO, DISPLAY, GPU, RESOLUTION, ENV, LOG, COMMAND, RESET, CONTROLLER, STORE, APP, UPDATE }

// ----------------------------------------------------------------- launcher

@Composable
fun Launcher() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("tuxbox", Context.MODE_PRIVATE) }
    val env = remember { LinuxEnv(ctx) }
    val state by SessionManager.state.collectAsStateWithLifecycle()
    val revision by SessionManager.revision.collectAsStateWithLifecycle()
    val log by SessionManager.log.collectAsStateWithLifecycle()

    var distroId by rememberSaveable { mutableStateOf(prefs.getString("distro", "debian")!!) }
    var desktopId by rememberSaveable { mutableStateOf(prefs.getString("desktop", "xfce")!!) }
    val (dw, dh) = remember { defaultResolution(ctx) }
    var resW by rememberSaveable {
        if (!prefs.getBoolean("resfix1", false)) prefs.edit().remove("w").remove("h").putBoolean("resfix1", true).apply()
        mutableStateOf(prefs.getInt("w", dw).toString())
    }
    var resH by rememberSaveable { mutableStateOf(prefs.getInt("h", dh).toString()) }
    var displayId by rememberSaveable { mutableStateOf(prefs.getString("display", "x11")!!) }
    var gpuId by rememberSaveable {
        mutableStateOf(prefs.getString("gpu", if (MesaDrivers.adrenoSupported()) "freedreno" else "virgl")!!)
    }
    var extraEnv by rememberSaveable { mutableStateOf(prefs.getString("env", "")!!) }
    var command by rememberSaveable { mutableStateOf("") }
    var dialog by remember { mutableStateOf(Dialog.NONE) }
    val flatpakJob by SessionManager.flatpakJob.collectAsStateWithLifecycle()
    var selectedApp by remember { mutableStateOf<LinuxEnv.FlatpakApp?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // ---- updates from GitHub Releases, checked once at start
    var update by remember { mutableStateOf<Updater.Release?>(null) }
    var updateChecked by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        update = Updater.check()
        updateChecked = true
        if (update != null) dialog = Dialog.UPDATE
    }
    fun runUpdate(r: Updater.Release) {
        if (!Updater.ensureInstallPermission(ctx)) return
        scope.launch {
            updateProgress = 0f
            runCatching { Updater.download(ctx, r) { updateProgress = it } }
                .onSuccess { Updater.install(ctx, it) }
                .onFailure { SessionManager.log("Update download failed: ${it.message}") }
            updateProgress = null
        }
    }

    val distro = Catalog.distro(distroId)
    val desktop = Catalog.desktop(desktopId)
    val rootfsReady = remember(distroId, revision, state) { env.isRootfsInstalled(distro) }
    val installed = remember(distroId, revision, state) { env.installedDesktops(distro) }
    val idle = state == SessionState.Idle
    val adreno = remember { MesaDrivers.adrenoModel() }
    val gpuCapable = remember { MesaDrivers.adrenoSupported() }
    val virgl = remember { env.virglAvailable() }
    val gpuInstalled = remember(distroId, revision, state) { env.gpuDriversInstalled(distro) }
    val flatpakApps = remember(distroId, revision, state, flatpakJob) { env.flatpakApps(distro) }
    val display = DisplayMode.of(displayId)
    val gpu = GpuMode.of(gpuId).let {
        when {
            it.adrenoOnly && !gpuCapable -> if (virgl) GpuMode.VIRGL else GpuMode.OFF
            it == GpuMode.VIRGL && !virgl -> GpuMode.OFF
            else -> it
        }
    }

    fun save() = prefs.edit().putString("distro", distroId).putString("desktop", desktopId)
        .putInt("w", resW.toIntOrNull() ?: dw).putInt("h", resH.toIntOrNull() ?: dh)
        .putString("display", displayId).putString("gpu", gpuId).putString("env", extraEnv).apply()

    fun openDesktop() {
        val running = (state as? SessionState.Running)?.desktop ?: desktop
        if (display == DisplayMode.TERMUX_X11 && !running.wayland)
            ctx.startActivity(Intent().setClassName(ctx.packageName, LinuxEnv.X11_ACTIVITY))
        else ctx.startActivity(Intent(ctx, DesktopActivity::class.java))
    }

    fun start() {
        val w = (resW.toIntOrNull() ?: dw).coerceIn(640, 3840)
        val h = (resH.toIntOrNull() ?: dh).coerceIn(480, 2160)
        SessionManager.start(ctx, distro, desktop, SessionManager.Options(w, h, display, gpu, extraEnv))
        openDesktop()
    }

    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { primaryFocus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Ink)) {
        // Backdrop: the selected desktop, big and dimmed (Daijishō-style box art).
        Crossfade(targetState = desktopId, label = "backdrop") { id ->
            DesktopPreview(id, Modifier.fillMaxSize().blur(40.dp))
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xB30B0D10), 0.45f to Color(0xE60B0D10), 0.7f to Ink)))

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ------------------------------------------------ top bar
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TuxBox", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Color.White)
                Spacer(Modifier.width(14.dp))
                StatusPill(state)
                Spacer(Modifier.weight(1f))
                Chip(onClick = { dialog = Dialog.DISTRO }, enabled = idle) {
                    DistroBadge(distro, 26.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(distro.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("  ▾", color = TextDim)
                }
            }

            // ------------------------------------------------ hero
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(desktop.name, fontSize = 36.sp, fontWeight = FontWeight.Black, color = Color.White, lineHeight = 38.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Tag(desktop.size)
                    if (desktop.id in installed) Tag("Installed", Accent)
                    if (desktop.wayland) Tag("Wayland")
                    if (!desktop.supports(distro)) Tag("Not on ${distro.name}", Color(0xFFE5484D))
                }
                Text(desktop.description, color = Color(0xFFD0D4DA), fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (!desktop.supports(distro) && desktop.note != null) Text(desktop.note, color = Color(0xFFFF8A80), fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val running = state is SessionState.Running
                    val canStart = idle && desktop.id in installed
                    val canInstall = idle && desktop.supports(distro)
                    when {
                        running -> {
                            BigButton("▶  Open desktop", Modifier.focusRequester(primaryFocus)) { openDesktop() }
                            BigButton("■  Stop", outlined = true) { SessionManager.stop() }
                        }
                        canStart -> {
                            BigButton("▶  Start", Modifier.focusRequester(primaryFocus)) { start() }
                            BigButton("Update", outlined = true, enabled = canInstall) { SessionManager.install(ctx, distro, desktop) }
                        }
                        else -> {
                            BigButton("⬇  Install", Modifier.focusRequester(primaryFocus), enabled = canInstall) {
                                SessionManager.install(ctx, distro, desktop)
                            }
                            if (state is SessionState.Busy) BigButton("■  Stop", outlined = true) { SessionManager.stop() }
                        }
                    }
                }
            }
            // Box art: the selected desktop's look
            Crossfade(targetState = desktopId, label = "art", modifier = Modifier.weight(0.9f)) { id ->
                DesktopPreview(
                    id,
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).shadow(18.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp)),
                )
            }
            }

            // ------------------------------------------------ row 1: desktops
            RowTitle("Desktops", "${installed.size} installed on ${distro.name}")
            val desktopsRow = rememberLazyListState(
                initialFirstVisibleItemIndex = Catalog.desktops.indexOfFirst { it.id == desktopId }.coerceAtLeast(0),
            )
            LazyRow(state = desktopsRow, horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp)) {
                items(Catalog.desktops, key = { it.id }) { de ->
                    DesktopTile(
                        de = de, selected = de.id == desktopId, installed = de.id in installed,
                        supported = de.supports(distro),
                        onFocus = { desktopId = de.id; save() },
                        onClick = {
                            if (de.id == desktopId && idle && de.id in installed) start()
                            else { desktopId = de.id; save() }
                        },
                    )
                }
            }

            // ------------------------------------------------ row: apps (Flatpak)
            RowTitle("Apps", flatpakJob ?: when {
                Flathub.deviceArch == null -> "Flathub has no apps for 32-bit devices"
                !rootfsReady -> "Install a desktop first"
                else -> "${flatpakApps.size} Flatpak apps · launch them on a running desktop"
            })
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp)) {
                item {
                    SettingTile("🛍", "Flathub store", "Browse & install", enabled = rootfsReady && Flathub.deviceArch != null) {
                        dialog = Dialog.STORE
                    }
                }
                items(flatpakApps, key = { it.id }) { a ->
                    AppTile(a.name, a.icon?.path, if (state is SessionState.Running) "Tap to open" else "Installed") {
                        selectedApp = a; dialog = Dialog.APP
                    }
                }
                if (flatpakApps.isNotEmpty()) item {
                    SettingTile("⟳", "Update apps", "All Flatpaks", enabled = flatpakJob == null) {
                        SessionManager.flatpak(ctx, distro, "update", label = "Updating Flatpak apps")
                    }
                }
            }

            // ------------------------------------------------ row 2: log
            RowTitle("Log", when (state) {
                SessionState.Idle -> "Idle"
                is SessionState.Busy -> (state as SessionState.Busy).task
                is SessionState.Running -> "Running"
            })
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp)) {
                item { LogTile(log) { dialog = Dialog.LOG } }
                item {
                    ActionTile("⌨", "Run command", "in ${distro.name}", enabled = idle && rootfsReady) { dialog = Dialog.COMMAND }
                }
                item { ActionTile("⌫", "Clear log", "${log.size} lines") { SessionManager.clearLog() } }
            }

            // ------------------------------------------------ row 3: settings
            RowTitle("Settings", null)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp)) {
                item {
                    SettingTile("🐧", "Distribution", distro.name, enabled = idle, badge = { DistroBadge(distro, 30.dp) }) {
                        dialog = Dialog.DISTRO
                    }
                }
                item { SettingTile("🖥", "Display", display.label, enabled = idle) { dialog = Dialog.DISPLAY } }
                item { SettingTile("⚡", "GPU", gpu.label, enabled = idle) { dialog = Dialog.GPU } }
                item { SettingTile("⤢", "Resolution", "${resW}×$resH", enabled = idle) { dialog = Dialog.RESOLUTION } }
                if (gpuCapable) item {
                    SettingTile(
                        "⬇", "GPU drivers",
                        when { !rootfsReady -> "Install a desktop first"; gpuInstalled -> "Installed · tap to update"; else -> "Not installed" },
                        enabled = idle && rootfsReady,
                    ) { SessionManager.installGpu(ctx, distro) }
                }
                item { SettingTile("🎮", "Controller", "Button map") { dialog = Dialog.CONTROLLER } }
                item {
                    SettingTile(
                        "⬆", "Updates",
                        when {
                            updateProgress != null -> "Downloading ${((updateProgress ?: 0f) * 100).toInt()}%"
                            update != null -> "v${update!!.version} available"
                            !updateChecked -> "Checking…"
                            else -> "v${Updater.currentVersion} · up to date"
                        },
                    ) {
                        if (update != null) dialog = Dialog.UPDATE
                        else scope.launch { updateChecked = false; update = Updater.check(); updateChecked = true; if (update != null) dialog = Dialog.UPDATE }
                    }
                }
                item {
                    SettingTile("⚙", "Environment", if (extraEnv.isBlank()) "None" else "${extraEnv.lines().count { it.isNotBlank() }} set",
                        enabled = idle) { dialog = Dialog.ENV }
                }
                item {
                    SettingTile("🗑", "Remove distro", if (rootfsReady) distro.name else "Not installed",
                        enabled = idle && rootfsReady, danger = true) { dialog = Dialog.RESET }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // ---------------------------------------------------- dialogs
    val close = { dialog = Dialog.NONE }
    when (dialog) {
        Dialog.DISTRO -> PickerDialog(
            "Distribution", close,
            Catalog.distros.map { d ->
                PickerOption(d.id, d.name, d.blurb + if (env.isRootfsInstalled(d)) "  · Installed" else "", leading = { DistroBadge(d, 34.dp) })
            },
            selected = distroId,
        ) { distroId = it; save(); close() }

        Dialog.DISPLAY -> PickerDialog(
            "Display", close,
            listOf(
                PickerOption(DisplayMode.TERMUX_X11.id, DisplayMode.TERMUX_X11.label,
                    "Built-in X server. Real GPU acceleration (Freedreno/Zink), blur and animations. Recommended."),
                PickerOption(DisplayMode.VNC.id, DisplayMode.VNC.label,
                    "Works everywhere and with Wayland desktops. GPU only through VirGL."),
            ),
            selected = displayId,
        ) { displayId = it; save(); close() }

        Dialog.GPU -> PickerDialog(
            "GPU" + (adreno?.let { " · $it" } ?: ""), close,
            GpuMode.entries.map { m ->
                PickerOption(m.id, m.label, m.help, enabled = when {
                    m.adrenoOnly -> gpuCapable
                    m == GpuMode.VIRGL -> virgl
                    else -> true
                })
            },
            selected = gpu.id,
        ) { gpuId = it; save(); close() }

        Dialog.RESOLUTION -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Resolution (VNC)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The X11 display always matches your screen; this is used by VNC.", color = TextDim, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(resW, { resW = it.filter(Char::isDigit).take(4) }, label = { Text("Width") },
                            singleLine = true, modifier = Modifier.width(110.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                        Text("×")
                        OutlinedTextField(resH, { resH = it.filter(Char::isDigit).take(4) }, label = { Text("Height") },
                            singleLine = true, modifier = Modifier.width(110.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { save(); close() }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { resW = "$dw"; resH = "$dh"; save(); close() }) { Text("Auto") } },
        )

        Dialog.ENV -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Environment variables") },
            text = {
                OutlinedTextField(
                    extraEnv, { extraEnv = it }, modifier = Modifier.fillMaxWidth(), minLines = 4,
                    label = { Text("KEY=value, one per line") },
                    placeholder = { Text("MESA_GL_VERSION_OVERRIDE=4.6\nTU_DEBUG=noconform,sysmem") },
                )
            },
            confirmButton = { TextButton(onClick = { save(); close() }) { Text("Save") } },
        )

        Dialog.LOG -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Log") },
            text = { LogList(log, Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 460.dp)) },
            confirmButton = { TextButton(onClick = close) { Text("Close") } },
            dismissButton = { TextButton(onClick = { SessionManager.clearLog() }) { Text("Clear") } },
        )

        Dialog.COMMAND -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Run command in ${distro.name}") },
            text = {
                OutlinedTextField(command, { command = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("apt install neofetch") })
            },
            confirmButton = {
                TextButton(enabled = command.isNotBlank(), onClick = {
                    SessionManager.shell(ctx, distro, command); command = ""; dialog = Dialog.LOG
                }) { Text("Run") }
            },
            dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        )

        Dialog.RESET -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Remove ${distro.name}?") },
            text = { Text("This deletes the whole Linux system and every desktop installed in it. Files in /root/android are kept.") },
            confirmButton = { TextButton(onClick = { close(); SessionManager.reset(ctx, distro) }) { Text("Remove", color = Color(0xFFFF6B6B)) } },
            dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        )

        Dialog.CONTROLLER -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Controller") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "Left stick" to "Mouse", "Right stick" to "Scroll", "D-pad" to "Arrow keys",
                        "A" to "Left click (hold to drag)", "B" to "Right click", "L3" to "Middle click",
                        "X" to "Enter", "Y" to "Backspace", "Select" to "Esc", "Start" to "On-screen keyboard",
                        "L1 (hold)" to "Super — e.g. L1+Q terminal, L1+R apps", "R1 (hold)" to "Alt",
                        "L2 (hold)" to "Ctrl", "R2" to "Tab", "R3" to "Show/hide key bar",
                    ).forEach { (k, v) ->
                        Row {
                            Text(k, Modifier.width(110.dp), fontWeight = FontWeight.Bold, color = Accent)
                            Text(v)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = close) { Text("OK") } },
        )

        Dialog.STORE -> StoreScreen(
            installedIds = flatpakApps.map { it.id }.toSet(),
            job = flatpakJob,
            onInstall = { app -> SessionManager.flatpak(ctx, distro, "install", app.id, "Installing ${app.name}") },
            onDismiss = close,
        )

        Dialog.APP -> selectedApp?.let { a ->
            AlertDialog(
                onDismissRequest = close,
                icon = { AppIcon(a.icon?.path, Modifier.size(56.dp)) },
                title = { Text(a.name) },
                text = {
                    Text(
                        a.id + "\n\n" + if (state is SessionState.Running) "Opens on your running desktop."
                        else "Start a desktop first, then open it from here or from the desktop's app menu.",
                        color = TextDim,
                    )
                },
                confirmButton = {
                    TextButton(enabled = state is SessionState.Running, onClick = {
                        SessionManager.launchFlatpak(ctx, distro, a.id); close(); openDesktop()
                    }) { Text("Open") }
                },
                dismissButton = {
                    TextButton(enabled = flatpakJob == null, onClick = {
                        SessionManager.flatpak(ctx, distro, "remove", a.id, "Removing ${a.name}"); close()
                    }) { Text("Uninstall", color = Color(0xFFFF6B6B)) }
                },
            )
        }

        Dialog.UPDATE -> update?.let { r ->
            AlertDialog(
                onDismissRequest = close,
                title = { Text("TuxBox ${r.version} is available") },
                text = {
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        Text("You have ${Updater.currentVersion}. Your distros and desktops are kept.", color = TextDim)
                        if (r.notes.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text(r.notes.take(1500), fontSize = 13.sp) }
                        updateProgress?.let { Spacer(Modifier.height(10.dp)); Text("Downloading… ${(it * 100).toInt()}%", color = Accent) }
                    }
                },
                confirmButton = {
                    TextButton(enabled = updateProgress == null, onClick = { runUpdate(r) }) { Text("Update") }
                },
                dismissButton = { TextButton(onClick = close) { Text("Later") } },
            )
        }

        Dialog.NONE -> Unit
    }
}

// ----------------------------------------------------------------- pieces

/** Scales up and outlines when focused with a D-pad/controller, like a console launcher. */
@Composable
private fun Modifier.focusGlow(shape: RoundedCornerShape, selected: Boolean = false, onFocus: () -> Unit = {}): Modifier {
    var focused by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (focused) 1.06f else 1f, label = "focus")
    return this
        .scale(s)
        .onFocusChanged { if (it.isFocused && !focused) onFocus(); focused = it.isFocused }
        .border(
            when {
                focused -> BorderStroke(3.dp, Color.White)
                selected -> BorderStroke(2.dp, Accent)
                else -> BorderStroke(1.dp, Color(0x22FFFFFF))
            },
            shape,
        )
}

@Composable
private fun DesktopTile(de: Desktop, selected: Boolean, installed: Boolean, supported: Boolean, onFocus: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.width(200.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .focusGlow(shape, selected, onFocus).clip(shape)
                .clickable(onClick = onClick),
        ) {
            DesktopPreview(de.id, Modifier.fillMaxSize())
            if (!supported) Box(Modifier.fillMaxSize().background(Color(0xAA000000)))
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (installed) Tag("✓", Accent)
                if (de.wayland) Tag("W")
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(de.name, color = if (supported) Color.White else TextDim, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            if (installed) "Installed" else if (supported) de.size else "Not available",
            color = TextDim, fontSize = 12.sp,
        )
    }
}

@Composable
private fun AppTile(name: String, icon: String?, subtitle: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.width(130.dp).height(130.dp).focusGlow(shape).clip(shape).background(Panel)
            .clickable(onClick = onClick).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        AppIcon(icon, Modifier.size(56.dp))
        Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = TextDim, fontSize = 11.sp, maxLines = 1)
    }
}

/** Full-screen Flathub browser: popular apps, search, install. */
@Composable
private fun StoreScreen(installedIds: Set<String>, job: String?, onInstall: (Flathub.StoreApp) -> Unit, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Flathub.StoreApp>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query) {
        if (query.isNotBlank()) kotlinx.coroutines.delay(400) // debounce typing
        error = null
        results = runCatching { if (query.isBlank()) Flathub.popular() else Flathub.search(query) }
            .onFailure { error = "Couldn't reach Flathub: ${it.message}" }.getOrNull()
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(Modifier.fillMaxSize().background(Ink).safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Flathub", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Color.White)
                Spacer(Modifier.width(16.dp))
                OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.weight(1f),
                    placeholder = { Text("Search apps (Firefox, GIMP, VLC, LibreOffice…)") })
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = onDismiss) { Text("Close") }
            }
            if (job != null) Text("⏳ $job — this runs in the background; you can close this screen.", color = Accent)
            Text("Apps run without Flatpak's sandbox (Android doesn't allow it) and with software graphics. " +
                "The first app downloads a shared runtime (~300–900 MB).", color = TextDim, fontSize = 12.sp)
            error?.let { Text(it, color = Color(0xFFFF8A80)) }
            val list = results
            if (list == null && error == null) Text("Loading…", color = TextDim)
            if (list != null) LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { a ->
                    val shape = RoundedCornerShape(12.dp)
                    val ok = Flathub.runsHere(a)
                    Row(
                        Modifier.fillMaxWidth().focusGlow(shape).clip(shape).background(Panel).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIcon(a.icon, Modifier.size(52.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name, color = Color.White, fontWeight = FontWeight.Bold)
                            Text(a.summary, color = TextDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(12.dp))
                        when {
                            a.id in installedIds -> Tag("Installed", Accent)
                            !ok -> Tag("Not for this CPU")
                            else -> OutlinedButton(onClick = { onInstall(a) }, enabled = job == null) { Text("Install", color = Color.White) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogTile(lines: List<String>, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.width(520.dp).height(130.dp).focusGlow(shape).clip(shape)
            .background(Color(0xE6101418)).clickable(onClick = onClick).padding(14.dp),
    ) {
        val tail = lines.takeLast(5)
        if (tail.isEmpty()) Text("Nothing yet. Pick a desktop and press Install.", color = TextDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        tail.forEach {
            Text(it, color = Color(0xFFD6DEE6), fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LogList(lines: List<String>, modifier: Modifier) {
    val st = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) st.scrollToItem(lines.lastIndex) }
    LazyColumn(modifier.background(Color(0xFF101418), RoundedCornerShape(8.dp)).padding(8.dp), state = st) {
        items(lines) { Text(it, color = Color(0xFFD6DEE6), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp) }
    }
}

@Composable
private fun ActionTile(icon: String, title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) =
    SettingTile(icon, title, subtitle, enabled = enabled, onClick = onClick)

@Composable
private fun SettingTile(
    icon: String, title: String, value: String, enabled: Boolean = true, danger: Boolean = false,
    badge: (@Composable () -> Unit)? = null, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.width(160.dp).height(130.dp).focusGlow(shape).clip(shape)
            .background(if (enabled) Panel else Color(0x66161A20))
            .clickable(enabled = enabled, onClick = onClick).padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        if (badge != null) badge() else Text(icon, fontSize = 28.sp)
        Column {
            Text(title, color = if (danger) Color(0xFFFF8A80) else Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(value, color = TextDim, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RowTitle(title: String, subtitle: String?) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (subtitle != null) Text("   $subtitle", color = TextDim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun StatusPill(state: SessionState) {
    val (label, color) = when (state) {
        SessionState.Idle -> "Idle" to Color(0xFF5F6B7A)
        is SessionState.Busy -> "Working…" to Color(0xFFE8A33D)
        is SessionState.Running -> "${state.desktop.name} running" to Color(0xFF3FB950)
    }
    Row(
        Modifier.background(Color(0x99000000), RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
        Spacer(Modifier.width(7.dp))
        Text(label, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun Tag(text: String, color: Color = Color(0xFF5F6B7A)) {
    Text(
        text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(color.copy(alpha = 0.85f), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun Chip(onClick: () -> Unit, enabled: Boolean, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.focusGlow(shape).clip(shape).background(Color(0x99000000))
            .clickable(enabled = enabled, onClick = onClick).padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun BigButton(text: String, modifier: Modifier = Modifier, outlined: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val m = modifier.focusGlow(shape).height(52.dp)
    if (outlined) OutlinedButton(onClick, m, enabled = enabled, shape = shape) { Text(text, fontSize = 16.sp, color = Color.White) }
    else Button(onClick, m, enabled = enabled, shape = shape,
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Ink)) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

private data class PickerOption(
    val id: String, val label: String, val description: String, val enabled: Boolean = true,
    val leading: (@Composable () -> Unit)? = null,
)

@Composable
private fun PickerDialog(title: String, onDismiss: () -> Unit, options: List<PickerOption>, selected: String, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.heightIn(max = 460.dp)) {
                items(options) { o ->
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        Modifier.fillMaxWidth().focusGlow(shape, o.id == selected).clip(shape)
                            .background(if (o.id == selected) PanelHi else Color(0xFF1B2028))
                            .clickable(enabled = o.enabled) { onPick(o.id) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        o.leading?.let { it(); Spacer(Modifier.width(12.dp)) }
                        Column(Modifier.weight(1f)) {
                            Text(o.label, fontWeight = FontWeight.Bold, color = if (o.enabled) Color.White else TextDim)
                            Text(o.description, fontSize = 12.sp, color = TextDim)
                        }
                        if (o.id == selected) Text("✓", color = Accent, fontSize = 18.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
