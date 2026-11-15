package dev.tuxbox

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
        }
        setContent { AppTheme { Surface(Modifier.fillMaxSize()) { Home() } } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Home() {
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
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var command by rememberSaveable { mutableStateOf("") }

    val distro = Catalog.distro(distroId)
    val desktop = Catalog.desktop(desktopId)
    val rootfsReady = remember(distroId, revision, state) { env.isRootfsInstalled(distro) }
    val installed = remember(distroId, revision, state) { env.installedDesktops(distro) }
    val idle = state == SessionState.Idle
    val adreno = remember { MesaDrivers.adrenoModel() }
    val gpuCapable = remember { MesaDrivers.adrenoSupported() }
    val virgl = remember { env.virglAvailable() }
    val gpuInstalled = remember(distroId, revision, state) { env.gpuDriversInstalled(distro) }
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

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("TuxBox", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Run a full Linux desktop on Android — no root needed.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        StatusCard(state, onOpen = ::openDesktop, onStop = SessionManager::stop)

        Section("1 · Distribution")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Catalog.distros.forEach { d ->
                FilterChip(
                    selected = d.id == distroId,
                    onClick = { distroId = d.id; save() },
                    enabled = idle,
                    label = { Text(d.name) },
                )
            }
        }
        Text(
            distro.blurb + "  " + if (rootfsReady) "Installed · ${installed.size} desktop(s)."
            else "Not installed yet; it downloads on first install.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Section("2 · Desktop environment")
        Catalog.desktops.forEach { de ->
            DesktopCard(
                de = de,
                selected = de.id == desktopId,
                installed = de.id in installed,
                supported = de.supports(distro),
                enabled = idle,
                onClick = { desktopId = de.id; save() },
            )
        }

        Section("3 · Resolution")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                resW, { resW = it.filter(Char::isDigit).take(4); save() },
                label = { Text("Width") }, singleLine = true, modifier = Modifier.width(110.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Text("×")
            OutlinedTextField(
                resH, { resH = it.filter(Char::isDigit).take(4); save() },
                label = { Text("Height") }, singleLine = true, modifier = Modifier.width(110.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            TextButton(onClick = { resW = "$dw"; resH = "$dh"; save() }) { Text("Auto") }
        }

        Section("4 · Display & GPU")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DisplayMode.entries.forEach { m ->
                FilterChip(
                    selected = display == m,
                    onClick = { displayId = m.id; save() },
                    enabled = idle,
                    label = { Text(m.label) },
                )
            }
        }
        Hint(
            when {
                desktop.wayland -> "${desktop.name} is Wayland-only, so it always uses VNC."
                display == DisplayMode.TERMUX_X11 -> "Built-in X server (from Termux:X11): GPU acceleration, blur and animations. " +
                    "Controller: left stick = mouse, A/B = click, right stick = scroll, L1 = Super, Start = keyboard."
                else -> "Works everywhere; GPU only through VirGL. Same controller support."
            },
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GpuMode.entries.forEach { m ->
                FilterChip(
                    selected = gpu == m,
                    onClick = { gpuId = m.id; save() },
                    enabled = idle && when {
                        m.adrenoOnly -> gpuCapable
                        m == GpuMode.VIRGL -> virgl
                        else -> true
                    },
                    label = { Text(m.label) },
                )
            }
        }
        Hint(
            (if (gpuCapable) "GPU: ${adreno ?: "Adreno"}" else "No Adreno GPU: VirGL is the fast option here") +
                " · ${gpu.help}",
        )
        if (gpuCapable && rootfsReady) {
            OutlinedButton(onClick = { SessionManager.installGpu(ctx, distro) }, enabled = idle) {
                Text(if (gpuInstalled) "Reinstall GPU drivers" else "Install GPU drivers (~12 MB)")
            }
            if (!gpuInstalled && gpu.adrenoOnly) Hint("GPU drivers aren't installed in ${distro.name} yet; it will run in software until you install them.")
        }

        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(if (showAdvanced) "Hide advanced" else "Advanced: environment variables")
        }
        if (showAdvanced) {
            OutlinedTextField(
                extraEnv, { extraEnv = it; save() },
                label = { Text("Extra env (KEY=value, one per line)") },
                placeholder = { Text("MESA_GL_VERSION_OVERRIDE=4.6\nTU_DEBUG=noconform,sysmem") },
                modifier = Modifier.fillMaxWidth(), minLines = 3,
            )
        }

        val canInstall = idle && desktop.supports(distro)
        val canStart = idle && desktop.id in installed
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { SessionManager.install(ctx, distro, desktop) }, enabled = canInstall) {
                Text(if (desktop.id in installed) "Update ${desktop.name}" else "Install ${desktop.name}")
            }
            Button(
                onClick = {
                    val w = (resW.toIntOrNull() ?: dw).coerceIn(640, 3840)
                    val h = (resH.toIntOrNull() ?: dh).coerceIn(480, 2160)
                    SessionManager.start(
                        ctx, distro, desktop,
                        SessionManager.Options(w, h, display, gpu, extraEnv),
                    )
                    openDesktop()
                },
                enabled = canStart,
            ) { Text("Start") }
        }
        if (!desktop.supports(distro)) {
            Text(
                "${desktop.name} isn't available on ${distro.name}. ${desktop.note.orEmpty()}",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            )
        }

        Section("Log")
        LogView(log)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                command, { command = it }, singleLine = true, modifier = Modifier.weight(1f),
                label = { Text("Run command in ${distro.name.substringBefore(" ")}") },
                enabled = idle && rootfsReady,
            )
            Button(
                onClick = { SessionManager.shell(ctx, distro, command); command = "" },
                enabled = idle && rootfsReady && command.isNotBlank(),
            ) { Text("Run") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = SessionManager::clearLog) { Text("Clear log") }
            TextButton(onClick = { confirmReset = true }, enabled = idle && rootfsReady) {
                Text("Remove ${distro.name.substringBefore(" ")}…", color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Remove ${distro.name}?") },
            text = { Text("This deletes the whole Linux system and every desktop installed in it. Files in /root/android are kept.") },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; SessionManager.reset(ctx, distro) }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}


@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun StatusCard(state: SessionState, onOpen: () -> Unit, onStop: () -> Unit) {
    val (title, color) = when (state) {
        SessionState.Idle -> "Idle" to MaterialTheme.colorScheme.surfaceVariant
        is SessionState.Busy -> state.task to MaterialTheme.colorScheme.tertiaryContainer
        is SessionState.Running -> "${state.desktop.name} running on ${state.distro.name}" to
            MaterialTheme.colorScheme.primaryContainer
    }
    Card(colors = CardDefaults.cardColors(containerColor = color), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.Medium)
            if (state is SessionState.Running) TextButton(onClick = onOpen) { Text("Open") }
            if (state != SessionState.Idle) TextButton(onClick = onStop) { Text("Stop") }
        }
    }
}

@Composable
private fun DesktopCard(
    de: Desktop, selected: Boolean, installed: Boolean, supported: Boolean, enabled: Boolean, onClick: () -> Unit,
) {
    val border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        border = border,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(de.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    color = if (supported) Color.Unspecified else MaterialTheme.colorScheme.outline)
                if (installed) Tag("Installed")
                if (de.wayland) Tag("Wayland")
                Text(de.size, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
            }
            Text(de.description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selected && de.note != null) {
                Text(de.note, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text, fontSize = 10.sp, fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.padding(start = 4.dp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun LogView(lines: List<String>) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth().height(260.dp)
            .background(Color(0xFF101214), RoundedCornerShape(8.dp)).padding(8.dp),
    ) {
        if (lines.isEmpty()) item {
            Text("Nothing yet. Pick a distro and desktop, then tap Install.",
                color = Color(0xFF8A9199), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        }
        items(lines) {
            Text(it, color = Color(0xFFD6DEE6), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}
