package dev.tuxbox

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Hand-drawn mock-ups of each desktop's layout and colours, used as "box art" in the launcher.
 * Drawn in code, so no screenshots or logos are bundled.
 */
@Composable
fun DesktopPreview(id: String, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        when (id) {
            "xfce" -> xfce()
            "lxde" -> lxde()
            "lxqt" -> lxqt()
            "mate" -> mate()
            "cinnamon" -> cinnamon()
            "kde" -> kde()
            "gnome" -> gnome()
            "i3" -> i3()
            "sway" -> sway()
            "hyprstyle" -> hypr()
            else -> drawRect(Color.DarkGray)
        }
    }
}

// ------------------------------------------------------------------ helpers

private fun DrawScope.u(f: Float) = size.minDimension * f
private fun DrawScope.px(fx: Float, fy: Float) = Offset(size.width * fx, size.height * fy)
private fun DrawScope.sz(fw: Float, fh: Float) = Size(size.width * fw, size.height * fh)

private fun DrawScope.bg(top: Color, bottom: Color) =
    drawRect(Brush.verticalGradient(listOf(top, bottom)))

private fun DrawScope.diag(a: Color, b: Color) =
    drawRect(Brush.linearGradient(listOf(a, b), Offset.Zero, Offset(size.width, size.height)))

private fun DrawScope.box(x: Float, y: Float, w: Float, h: Float, c: Color, r: Float = 0f) =
    drawRoundRect(c, px(x, y), sz(w, h), CornerRadius(u(r)))

/** A window: title bar + body, optionally with a few "text lines". */
private fun DrawScope.window(
    x: Float, y: Float, w: Float, h: Float, title: Color, body: Color, r: Float = 0.01f,
    lines: Color? = null, titleH: Float = 0.06f,
) {
    box(x, y, w, h, body, r)
    box(x, y, w, titleH, title, r)
    if (lines != null) for (i in 0 until 4) box(x + w * 0.06f, y + titleH + h * (0.12f + i * 0.13f), w * (0.75f - i * 0.12f), h * 0.04f, lines, 0.004f)
}

private fun DrawScope.waves(layers: List<Color>) {
    layers.forEachIndexed { i, c ->
        val p = Path()
        val y0 = 0.55f + i * 0.1f
        p.moveTo(0f, size.height)
        var x = 0f
        while (x <= size.width) {
            val t = x / size.width
            p.lineTo(x, size.height * (y0 + 0.07f * sin(2 * PI * (t * (1 + i * 0.3) + i * 0.4)).toFloat()))
            x += size.width / 40
        }
        p.lineTo(size.width, size.height); p.close()
        drawPath(p, c)
    }
}

private fun DrawScope.icons(x: Float, y: Float, n: Int, step: Float, s: Float, c: Color) {
    for (i in 0 until n) box(x + i * step, y, s, s * size.width / size.height, c, 0.008f)
}

// ------------------------------------------------------------------ desktops

private fun DrawScope.xfce() {
    bg(Color(0xFF3A6EA5), Color(0xFF0E2A47))
    box(0f, 0f, 1f, 0.06f, Color(0xFF2B2E33))                       // top panel
    box(0.01f, 0.012f, 0.08f, 0.036f, Color(0xFF4A90D9), 0.006f)
    window(0.08f, 0.16f, 0.5f, 0.55f, Color(0xFF3C3F44), Color(0xFF1E1F22), lines = Color(0xFF6FBF73))
    window(0.45f, 0.3f, 0.45f, 0.45f, Color(0xFFDADADA), Color(0xFFF5F5F5), lines = Color(0xFFB0B0B0))
    box(0.36f, 0.88f, 0.28f, 0.09f, Color(0xCC2B2E33), 0.02f)         // dock
    icons(0.38f, 0.9f, 5, 0.05f, 0.035f, Color(0xFF8AB4F8))
}

private fun DrawScope.lxde() {
    bg(Color(0xFF4F7CAC), Color(0xFF2C4F75))
    window(0.1f, 0.12f, 0.55f, 0.6f, Color(0xFF6C8EBF), Color(0xFFEDEDED), 0f, Color(0xFFBBBBBB))
    box(0f, 0.92f, 1f, 0.08f, Color(0xFFD9D9D9))                     // bottom panel
    box(0.01f, 0.935f, 0.05f, 0.05f, Color(0xFF3465A4), 0.006f)
    icons(0.08f, 0.935f, 3, 0.045f, 0.03f, Color(0xFF777777))
    box(0.25f, 0.935f, 0.18f, 0.05f, Color(0xFFBDBDBD))
}

private fun DrawScope.lxqt() {
    diag(Color(0xFF2C3E63), Color(0xFF121826))
    window(0.12f, 0.12f, 0.6f, 0.62f, Color(0xFF31363B), Color(0xFF232629), lines = Color(0xFF3DAEE9))
    box(0f, 0.92f, 1f, 0.08f, Color(0xFF1B1E20))
    box(0.01f, 0.935f, 0.05f, 0.05f, Color(0xFF3DAEE9), 0.01f)
    icons(0.08f, 0.935f, 4, 0.045f, 0.03f, Color(0xFF8899AA))
}

private fun DrawScope.mate() {
    bg(Color(0xFF5E8C52), Color(0xFF243B1F))
    box(0f, 0f, 1f, 0.06f, Color(0xFFD6D2C9))
    box(0f, 0.94f, 1f, 0.06f, Color(0xFFD6D2C9))
    window(0.15f, 0.15f, 0.55f, 0.6f, Color(0xFF87A556), Color(0xFFF2F1EC), 0.01f, Color(0xFFBDBAAE))
}

private fun DrawScope.cinnamon() {
    diag(Color(0xFF2E4A56), Color(0xFF101C22))
    window(0.1f, 0.12f, 0.48f, 0.6f, Color(0xFF3A3A3A), Color(0xFF2B2B2B), 0.015f, Color(0xFF8FB8C9))
    window(0.45f, 0.22f, 0.45f, 0.55f, Color(0xFFE6E6E6), Color(0xFFFAFAFA), 0.015f, Color(0xFFC0C0C0))
    box(0f, 0.91f, 1f, 0.09f, Color(0xFF2B2B2B))
    box(0.01f, 0.925f, 0.06f, 0.06f, Color(0xFF8FB8C9), 0.01f)
    icons(0.09f, 0.93f, 4, 0.045f, 0.03f, Color(0xFFBBBBBB))
}

private fun DrawScope.kde() {
    diag(Color(0xFF1D99F3), Color(0xFF232629))
    window(0.1f, 0.1f, 0.5f, 0.62f, Color(0xFF31363B), Color(0xFF232629), 0.012f, Color(0xFF3DAEE9))
    window(0.48f, 0.2f, 0.42f, 0.5f, Color(0xFFDEE0E2), Color(0xFFEFF0F1), 0.012f, Color(0xFFBDC3C7))
    box(0.2f, 0.87f, 0.6f, 0.1f, Color(0xE6232629), 0.03f)           // floating panel
    box(0.22f, 0.89f, 0.04f, 0.06f, Color(0xFF3DAEE9), 0.01f)
    icons(0.3f, 0.895f, 6, 0.045f, 0.03f, Color(0xFFBDC3C7))
}

private fun DrawScope.gnome() {
    bg(Color(0xFF3D3846), Color(0xFF241F31))
    box(0f, 0f, 1f, 0.05f, Color(0xFF000000))
    box(0.46f, 0.012f, 0.08f, 0.026f, Color(0xFFDDDDDD), 0.005f)       // clock
    window(0.15f, 0.12f, 0.7f, 0.64f, Color(0xFFEBEBEB), Color(0xFFFAFAFA), 0.025f, Color(0xFFC0BFBC), 0.07f)
    box(0.3f, 0.86f, 0.4f, 0.1f, Color(0xB3303030), 0.03f)            // dash
    icons(0.33f, 0.88f, 6, 0.057f, 0.04f, Color(0xFF99C1F1))
}

private fun DrawScope.i3() {
    drawRect(Color(0xFF1A1A1A))
    val border = Color(0xFF4C7899)
    box(0.005f, 0.01f, 0.5f, 0.92f, border); box(0.01f, 0.03f, 0.49f, 0.89f, Color(0xFF111111))
    box(0.51f, 0.01f, 0.485f, 0.455f, Color(0xFF333333)); box(0.515f, 0.03f, 0.475f, 0.43f, Color(0xFF181818))
    box(0.51f, 0.475f, 0.485f, 0.455f, Color(0xFF333333)); box(0.515f, 0.495f, 0.475f, 0.43f, Color(0xFF181818))
    for (i in 0 until 6) box(0.03f, 0.08f + i * 0.08f, 0.3f - i * 0.03f, 0.03f, Color(0xFF6A9955))
    box(0f, 0.94f, 1f, 0.06f, Color(0xFF000000))
    box(0.01f, 0.955f, 0.03f, 0.03f, Color(0xFF285577)); box(0.045f, 0.955f, 0.03f, 0.03f, Color(0xFF333333))
    box(0.7f, 0.955f, 0.28f, 0.03f, Color(0xFF555555))
}

private fun DrawScope.sway() {
    diag(Color(0xFF3B4252), Color(0xFF2E3440))
    box(0f, 0f, 1f, 0.06f, Color(0xFF1D1F21))
    box(0.01f, 0.012f, 0.03f, 0.036f, Color(0xFF88C0D0))
    val g = 0.02f
    box(g, 0.08f, 0.48f - g, 0.9f, Color(0xFF88C0D0), 0.004f); box(g + 0.004f, 0.087f, 0.48f - g - 0.008f, 0.886f, Color(0xFF242933))
    box(0.5f + g / 2, 0.08f, 0.48f - g, 0.44f, Color(0xFF4C566A), 0.004f); box(0.5f + g / 2 + 0.004f, 0.087f, 0.48f - g - 0.008f, 0.426f, Color(0xFF242933))
    box(0.5f + g / 2, 0.54f, 0.48f - g, 0.44f, Color(0xFF4C566A), 0.004f); box(0.5f + g / 2 + 0.004f, 0.547f, 0.48f - g - 0.008f, 0.426f, Color(0xFF242933))
    for (i in 0 until 6) box(0.05f, 0.14f + i * 0.08f, 0.3f - i * 0.03f, 0.03f, Color(0xFFA3BE8C))
}

private fun DrawScope.hypr() {
    bg(Color(0xFFF0F0F0), Color(0xFFBDBDBD))
    waves(listOf(Color(0xFFFAFAFA), Color(0xFFD5D5D5), Color(0xFF8E8E8E), Color(0xFF3A3A3A)))
    val pill = Color(0xE6FFFFFF)
    box(0.01f, 0.02f, 0.2f, 0.065f, pill, 0.03f)
    drawCircle(Color(0xFF1F2329), u(0.022f), px(0.13f, 0.0525f))
    box(0.44f, 0.02f, 0.12f, 0.065f, pill, 0.03f)
    box(0.8f, 0.02f, 0.19f, 0.065f, pill, 0.03f)
    box(0.02f, 0.12f, 0.47f, 0.7f, Color(0xD91A1A1A), 0.03f)          // translucent terminals
    box(0.51f, 0.12f, 0.47f, 0.7f, Color(0xCC1A1A1A), 0.03f)
    for (i in 0 until 4) box(0.05f, 0.17f + i * 0.07f, 0.25f - i * 0.04f, 0.03f, Color(0xFFDEDEDE), 0.01f)
    box(0.36f, 0.86f, 0.28f, 0.1f, pill, 0.03f)                      // dock
    icons(0.39f, 0.885f, 5, 0.047f, 0.03f, Color(0xFF1F2329))
}

// ------------------------------------------------------------------ distros

private val distroColors = mapOf(
    "debian" to Color(0xFFA80030), "ubuntu" to Color(0xFFE95420), "kali" to Color(0xFF367BF0),
    "devuan" to Color(0xFF5A4E7C), "arch" to Color(0xFF1793D1), "fedora" to Color(0xFF51A2DA),
    "opensuse" to Color(0xFF73BA25), "alpine" to Color(0xFF0D597F), "void" to Color(0xFF478061),
)

fun distroColor(id: String) = distroColors[id] ?: Color.Gray

/** Coloured round badge with the distro's initials (not its trademarked logo). */
@Composable
fun DistroBadge(d: Distro, size: Dp = 40.dp) {
    val initials = d.name.split(' ', '"').filter { it.isNotBlank() }.let {
        if (d.id == "opensuse") "SU" else it.first().take(2)
    }
    Box(
        Modifier.size(size).background(distroColor(d.id), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.36f).sp)
    }
}
