package dev.tuxbox

import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Flathub catalogue (https://flathub.org/api/v2) for the in-app store. */
object Flathub {
    data class StoreApp(val id: String, val name: String, val summary: String, val icon: String?, val arches: List<String>)

    /** Flathub's name for this device's CPU; null when Flathub has no builds for it (32-bit ARM). */
    val deviceArch: String? = when (Build.SUPPORTED_ABIS.first()) {
        "arm64-v8a" -> "aarch64"; "x86_64" -> "x86_64"; else -> null
    }

    fun runsHere(a: StoreApp) = deviceArch != null && (a.arches.isEmpty() || deviceArch in a.arches)

    private fun http(url: String, body: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", "TuxBox")
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        if (body != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        return c.inputStream.bufferedReader().readText()
    }

    private fun parse(hits: JSONArray) = (0 until hits.length()).map { i ->
        val h = hits.getJSONObject(i)
        StoreApp(
            h.getString("app_id"), h.optString("name", h.getString("app_id")), h.optString("summary"),
            h.optString("icon").takeIf { it.startsWith("http") },
            h.optJSONArray("arches")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        )
    }

    suspend fun popular(): List<StoreApp> = withContext(Dispatchers.IO) {
        parse(JSONObject(http("https://flathub.org/api/v2/collection/popular?page=1&per_page=60")).getJSONArray("hits"))
    }

    suspend fun search(query: String): List<StoreApp> = withContext(Dispatchers.IO) {
        parse(JSONObject(http("https://flathub.org/api/v2/search", JSONObject().put("query", query).toString())).getJSONArray("hits"))
    }

    // ------------------------------------------------------------------ icons

    private val cache = LruCache<String, ImageBitmap>(150)

    suspend fun icon(src: String): ImageBitmap? = cache.get(src) ?: withContext(Dispatchers.IO) {
        runCatching {
            val bytes = if (src.startsWith("http")) URL(src).openStream().use { it.readBytes() } else File(src).readBytes()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()?.also { cache.put(src, it) }
        }.getOrNull()
    }
}

/** Shows an icon from a URL or local file, with a placeholder while loading. */
@Composable
fun AppIcon(src: String?, modifier: Modifier) {
    var bmp by remember(src) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(src) { if (src != null) bmp = Flathub.icon(src) }
    val b = bmp
    if (b != null) Image(b, null, modifier)
    else Box(modifier.background(Color(0xFF2A303A), RoundedCornerShape(12.dp)))
}

