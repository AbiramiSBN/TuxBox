package dev.tuxbox

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Full-screen viewer for the desktop served on 127.0.0.1:5901. */
class DesktopActivity : ComponentActivity(), VncClient.Listener {
    private var status by mutableStateOf("Waiting for the desktop to start...")
    private var connected by mutableStateOf(false)
    private var failed by mutableStateOf(false)
    private val held = mutableStateMapOf<Int, Boolean>()
    private var barVisible by mutableStateOf(true)

    private var client: VncClient? = null
    private var view: VncView? = null
    @Volatile private var alive = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        barVisible = getSharedPreferences("tuxbox", MODE_PRIVATE).getBoolean("bar", true)
        val v = VncView(this, VncClient(this)).also {
            it.onModifiersReleased = { held.clear() }
            it.onToggleBar = { setBar(!barVisible) }
        }
        view = v
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Screen(v) } }
        connect()
    }

    private fun connect() {
        failed = false
        status = "Waiting for the desktop to start..."
        Thread {
            // The session may take a while to boot; keep retrying while it's running.
            for (attempt in 0 until 120) {
                if (!alive) return@Thread
                if (SessionManager.state.value !is SessionState.Running) {
                    runOnUiThread { status = "No desktop session is running."; failed = true }
                    return@Thread
                }
                try {
                    java.net.Socket().use { it.connect(java.net.InetSocketAddress(HOST, PORT), 500) }
                    break
                } catch (_: Exception) { Thread.sleep(1000) }
            }
            if (!alive) return@Thread
            Thread.sleep(500)
            val c = VncClient(this)
            client?.close()
            client = c
            view?.client = c
            c.run(HOST, PORT)
        }.start()
    }

    private fun setBar(visible: Boolean) {
        barVisible = visible
        getSharedPreferences("tuxbox", MODE_PRIVATE).edit().putBoolean("bar", visible).apply()
    }

    override fun onConnected(name: String) = runOnUiThread {
        connected = true; status = name
        view?.requestFocus() // so controller and keyboard input go to the desktop
    }
    override fun onFrame() { view?.postInvalidate() }
    override fun onDisconnected(error: String?) = runOnUiThread {
        connected = false; failed = true
        status = "Disconnected" + (error?.let { ": $it" } ?: "")
    }

    override fun onDestroy() {
        alive = false
        client?.close()
        super.onDestroy()
    }

    @Composable
    private fun Screen(v: VncView) {
        Column(Modifier.fillMaxSize().background(Color.Black).imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { v }, modifier = Modifier.fillMaxSize())
                if (connected && !barVisible) {
                    // Small handle to bring the key bar back.
                    TextButton(
                        onClick = { setBar(true) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                            .background(Color(0x99202020), androidx.compose.foundation.shape.RoundedCornerShape(50)),
                    ) { Text("⌨ ▴", color = Color.White, fontSize = 13.sp) }
                }
                if (!connected) {
                    Column(
                        Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (!failed) CircularProgressIndicator()
                        Text(status, color = Color.White, modifier = Modifier.padding(16.dp))
                        if (failed) Row {
                            Button(onClick = { connect() }) { Text("Retry") }
                            TextButton(onClick = { finish() }) { Text("Back") }
                        }
                    }
                }
            }
            if (connected && barVisible) KeyBar(v)
        }
    }

    @Composable
    private fun KeyBar(v: VncView) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).background(Color(0xFF1E1E1E))
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarKey("▾") { setBar(false) }
            BarKey("⌨") { v.toggleKeyboard() }
            BarKey("Esc") { v.tapKey(Keysyms.ESCAPE) }
            BarKey("Tab") { v.tapKey(Keysyms.TAB) }
            for ((label, sym) in listOf("Ctrl" to Keysyms.CTRL, "Alt" to Keysyms.ALT, "Super" to Keysyms.SUPER, "Shift" to Keysyms.SHIFT)) {
                BarKey(label, active = held[sym] == true) {
                    if (v.toggleModifier(sym)) held[sym] = true else held.remove(sym)
                }
            }
            BarKey("←") { v.tapKey(Keysyms.LEFT) }
            BarKey("↑") { v.tapKey(Keysyms.UP) }
            BarKey("↓") { v.tapKey(Keysyms.DOWN) }
            BarKey("→") { v.tapKey(Keysyms.RIGHT) }
            BarKey("Home") { v.tapKey(Keysyms.HOME) }
            BarKey("End") { v.tapKey(Keysyms.END) }
            BarKey("Del") { v.tapKey(Keysyms.DELETE) }
            for (i in 1..12) BarKey("F$i") { v.tapKey(Keysyms.F1 + i - 1) }
            BarKey("Hide ▾") { setBar(false) }
            BarKey("Exit") { finish() }
        }
    }

    @Composable
    private fun BarKey(label: String, active: Boolean = false, onClick: () -> Unit) {
        TextButton(onClick = onClick, modifier = Modifier.padding(horizontal = 1.dp)) {
            Text(label, color = if (active) Color(0xFF7FC8FF) else Color.White, fontSize = 14.sp)
        }
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 5901
    }
}
