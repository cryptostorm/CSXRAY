package com.cryptostorm.xray

import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.Activity
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.os.Bundle
import android.os.Build
import android.widget.Toast
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import java.net.Proxy
import java.net.InetSocketAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Job
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.cryptostorm.xray.ui.theme.CSXRAYTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.URL
import java.security.MessageDigest
import java.util.regex.Pattern
import kotlin.math.min

/*───────────────────────────────────────────────────────────────────────────*/
/*  Remote server-list update config                                         */
/*───────────────────────────────────────────────────────────────────────────*/
private const val ENDPOINT_PORT = 31338
private const val CACHE_FILE_SERVERS = "servers.json"
private const val PREFS_NAME = "csxray_prefs"
private const val PREF_HASH = "server_list_hash"
private const val PREF_LAST_CHECK = "server_list_last_check_ms"
private const val CHECK_PERIOD_MS = 60L * 60L * 1000L // 1 hour

// All updater traffic goes through the local SOCKS (Xray inbound).
private const val SOCKS_HOST = "127.0.0.1"
private const val SOCKS_PORT = 10808
private const val PREF_KEY_IPVER = "pref_ip_version"         // 4 or 6
private const val PREF_KEY_PORT  = "pref_port"
private const val PREF_KEY_SLUG  = "pref_selected_slug"
private const val PREF_KEY_SHOW_LOGS = "pref_show_logs"
private const val PREF_KEY_LANG = "pref_lang"
private const val WG_IDLE_MS = 5 * 60_000L  // 5 minutes (or 10 * 60_000L)

private const val PREF_NEXT_AT = "server_list_next_at_ms"
private const val PREF_LAST_WG_AT = "last_wg_traffic_ms"
private const val PREF_IS_CUSTOM = "pref_is_custom"
private const val PREF_CUSTOM_IP = "pref_custom_ip"
private const val PREF_CUSTOM_IP_V4 = "pref_custom_ip_v4"
private const val PREF_CUSTOM_IP_V6 = "pref_custom_ip_v6"
private const val PREF_DARK_MODE = "pref_dark_mode"
private const val PREF_CUSTOM_CFG = "pref_custom_cfg"

private const val PREF_API_PORT = "pref_api_port"
private const val PREF_API_ENABLED_BY_APP = "pref_api_enabled_by_app"

// App update notice
private const val PREF_APP_VER_NOTIFIED = "pref_app_ver_notified"
private const val LATEST_APK_URL = "https://cryptostorm.is/xray/app-latest.apk"
private const val PREF_LATEST_REMOTE_APP_VER = "latest_remote_app_ver"

// Safe helper to read our versionName without relying on BuildConfig
private fun getMyVersionName(context: Context): String {
    return try {
        val pm = context.packageManager
        val pkg = context.packageName
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)).versionName ?: ""
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0).versionName ?: ""
        }
    } catch (_: Throwable) { "" }
}

private fun minutesCeil(ms: Long): Long =
    ((ms + 59_999L) / 60_000L).coerceAtLeast(0L)

private fun scheduleNextCheckMs(prefs: android.content.SharedPreferences, now: Long = System.currentTimeMillis()): Long {
    // Enforce at most one check per hour
    val mins = kotlin.random.Random.nextInt(60, 81) // 60–80 minutes
    val jitterMs = kotlin.random.Random.nextLong(0, 30_001)
    val nextAt = now + mins * 60_000L + jitterMs
    prefs.edit().putLong(PREF_NEXT_AT, nextAt).apply()
    return nextAt
}

// ─────────────────────────────────────────────────────────────────────
// JSON validation helpers (pretty error with line/col and small context)
private fun parseCharIndexFromJSONException(msg: String?): Int? {
    if (msg.isNullOrBlank()) return null
    // Typical org.json message: "at character 399 of …"
    val match = Regex("""at character\s+(\d+)""", RegexOption.IGNORE_CASE)
        .find(msg) ?: return null
    return match.groupValues.getOrNull(1)?.toIntOrNull()
}

private data class LineCol(val line: Int, val col: Int)

private fun indexToLineCol(text: String, idx: Int): LineCol {
    var line = 1
    var col = 1
    var i = 0
    while (i < text.length && i < idx) {
        if (text[i] == '\n') { line++; col = 1 } else { col++ }
        i++
    }
    return LineCol(line = line, col = col)
}

private fun humanBytes(v: Long): String {
    val units = arrayOf("B","KB","MB","GB","TB","PB")
    var x = if (v < 0) 0L else v
    var i = 0
    var f = x.toDouble()
    while (f >= 1024.0 && i < units.lastIndex) { f /= 1024.0; i++ }
    return if (i == 0) "$x ${units[i]}" else String.format(java.util.Locale.US, "%.1f %s", f, units[i])
}

private fun errorSnippet(text: String, errorIdx: Int, contextLines: Int = 1): String {
    val lines = text.split('\n')
    val (line, col) = indexToLineCol(text, errorIdx)
    val start = (line - contextLines).coerceAtLeast(1)
    val end = (line + contextLines).coerceAtMost(lines.size)
    val width = end.toString().length
    val b = StringBuilder()
    for (ln in start..end) {
        val num = ln.toString().padStart(width, ' ')
        b.append(num).append(" | ").append(lines[ln - 1]).append('\n')
        if (ln == line) {
            b.append(" ".repeat(width)).append(" | ").append(" ".repeat((col - 1).coerceAtLeast(0))).append("^\n")
        }
    }
    return b.toString()
}

private fun validateJsonPretty(json: String): Pair<Boolean, String?> {
    return try {
        // Try both object/array since users may paste either as a root
        runCatching { org.json.JSONObject(json) }
            .recoverCatching { org.json.JSONArray(json) }
            .getOrThrow()
        true to null
    } catch (e: Throwable) {
        // Build concise message with line/col if possible
        val raw = e.message ?: e::class.java.simpleName
        val idx = parseCharIndexFromJSONException(raw)
        val msg = if (idx != null) {
            val (line, col) = indexToLineCol(json, idx)
            val snippet = errorSnippet(json, idx, contextLines = 1)
            "JSON syntax error at line $line, col $col\n$snippet"
        } else {
            "JSON syntax error: $raw"
        }
        false to msg
    }
}

private fun appendBootstrapErrorLine(context: Context, line: String) {
    runCatching {
        val f = File(context.filesDir, "error.log")
        f.appendText("[bootstrap] $line\n")
    }
}

// Write a bootstrap error to file *and* mirror it to the on-screen log.
private fun appendBootstrapErrorUI(
    context: Context,
    uiLog: MutableState<String>,
    line: String
) {
    appendBootstrapErrorLine(context, line)
    uiLog.value += "[bootstrap] $line\n"
}

// Small, monospace code editor with line-number gutter
@Composable
private fun CodeEditor(
    value: String,
    onValueChange: (String) -> Unit,
    readOnly: Boolean,
    errorLine: Int? = null,
    modifier: Modifier = Modifier
) {
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val lines = remember(value) { value.split('\n') }
    val digits = remember(lines.size) { maxOf(2, lines.size.toString().length) }
    val gutterWidth = (digits * 10 + 12).dp  // simple monospace-ish estimate
    // Text layout so we can compute the error line’s Y bounds and paint under it
    var layout: androidx.compose.ui.text.TextLayoutResult? by remember { mutableStateOf(null) }
    val errBg = MaterialTheme.colorScheme.error.copy(alpha = 0.42f) // brighter red
    // Visually indicate editability: lighter background when editable
    val editorBg = if (readOnly)
        MaterialTheme.colorScheme.surfaceVariant
    else
        MaterialTheme.colorScheme.surface
    val fieldBg = if (readOnly)
        Color.Unspecified
    else
        MaterialTheme.colorScheme.background

    Row(
        modifier = modifier
            .background(editorBg, RoundedCornerShape(8.dp))
            .padding(4.dp)
    ) {
        // Gutter
        Column(
            modifier = Modifier
                .width(gutterWidth)
                .verticalScroll(vScroll)
                .padding(end = 6.dp, start = 4.dp, top = 6.dp)
        ) {
            lines.forEachIndexed { i, _ ->
                val isErr = (errorLine != null && (i + 1) == errorLine)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (isErr) errBg else Color.Transparent, RoundedCornerShape(4.dp))
                        .padding(horizontal = 2.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = (i + 1).toString().padStart(digits, ' '),
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = MaterialTheme.typography.bodySmall.fontSize,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        )
                    )
                }
            }
        }
        // Editor
        Box(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(vScroll)
                .horizontalScroll(hScroll)
                .padding(6.dp)
                // Paint a full-width highlight behind the error line in the editor area
                .drawBehind {
                    val l = layout ?: return@drawBehind
                    val lineIndex = (errorLine ?: 0) - 1
                    if (lineIndex in 0 until l.lineCount) {
                        val top = l.getLineTop(lineIndex)
                        val bottom = l.getLineBottom(lineIndex)
                        drawRect(
                            color = errBg,
                            topLeft = androidx.compose.ui.geometry.Offset(0f, top),
                            size = androidx.compose.ui.geometry.Size(size.width, bottom - top)
                        )
                    }
                }
        ) {
            BasicTextField(
                value = value,
                onValueChange = { if (!readOnly) onValueChange(it) },
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                readOnly = readOnly,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                onTextLayout = { layout = it }
                ,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(fieldBg, RoundedCornerShape(6.dp))
                    .padding(8.dp)
            )
        }
    }
}


/**
 * Build updater endpoints for the currently selected node (wgSlug),
 * e.g. slug "dallas" -> https://dallas.cstorm.is/...
 *
 * This endpoint is used to check for new app versions, or any updates to the server
 * IPs. This HTTPS traffic will use an Xray SOCKS inbound to send the request over
 * REALITY so it looks the same as the obfuscated VPN traffic to anyone listening
 * in, and the updates happen at random intervals just to confuse any potential
 * timing-based/correlation attack.
 **/
private data class UpdateUrls(val hashUrl: String, val listUrl: String)

private fun makeUpdateUrlsFor(slug: String): UpdateUrls {
    val base = "https://${slug}.cstorm.is"
    return UpdateUrls(
        hashUrl = "${base}/list_hash.txt", // SHA-256 of the JSON list
        listUrl = "${base}/latest_list.json", // the latest JSON list
    )
}

/*───────────────────────────────────────────────────────────────────────────*/
data class ServerInfo(
    val name: String,
    val ipv4: String,
    val ipv6: String,
    val flagAsset: String,
    val wgSlug: String
)

/** IPv6 detector that ignores VPN and looks across Wi-Fi/Cellular/Ethernet.
 *  We only return true if:
 *   - the network is INTERNET + VALIDATED (so Android thinks it can reach the net)
 *   - there is a non-link-local global IPv6 address
 *   - there is a default IPv6 route
 */
private fun hasGlobalIPv6(cm: ConnectivityManager): Boolean {
    for (net in cm.allNetworks) {
        val caps = cm.getNetworkCapabilities(net) ?: continue

        // Ignore the VPN interface itself
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue

        // Must be a real internet-capable uplink and validated by Android
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) continue

        // Only care about normal uplinks
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            && !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) continue

        val lp: LinkProperties = cm.getLinkProperties(net) ?: continue

        // 1) Do we have a global (non-link-local) v6 address?
        val hasGlobalAddr = lp.linkAddresses.any { la ->
            val a = la.address
            a is Inet6Address &&
                    !a.isLinkLocalAddress &&
                    !a.isLoopbackAddress &&
                    !a.isAnyLocalAddress
        }

        if (!hasGlobalAddr) continue

        // 2) Do we have a default IPv6 route?
        val hasDefaultV6Route = lp.routes.any { r ->
            r.isDefaultRoute && (r.destination.address is Inet6Address)
        }

        if (hasDefaultV6Route) return true
    }
    return false
}

/*───────────────────────────────────────────────────────────────────────────*/
private fun loadStrings(context: Context): Map<String, Map<String, String>> {
    val bytes = context.assets.open("i18n/strings.json").use { it.readBytes() }
    val root = JSONObject(String(bytes, Charsets.UTF_8))
    val out = mutableMapOf<String, Map<String, String>>()
    for (lang in root.keys()) {
        val obj = root.getJSONObject(lang)
        val map = mutableMapOf<String, String>()
        for (k in obj.keys()) map[k] = obj.getString(k)
        out[lang] = map
    }
    return out
}

private data class LangOption(val code: String, val display: String, val flagAsset: String)

/*───────────────────────────────────────────────────────────────────────────*/
class MainActivity : ComponentActivity() {
    private var splashDismissed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= 31) {
            val splash = installSplashScreen()
            splash.setKeepOnScreenCondition { !splashDismissed }
        }
        super.onCreate(savedInstanceState)

        setContent {
            CSXRAYTheme {
                val ctx = this@MainActivity
                val prefs = remember { ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
                var darkMode by remember { mutableStateOf(prefs.getBoolean(PREF_DARK_MODE, false)) }
                CSXRAYTheme(darkTheme = darkMode) {
                    val owner = LocalOnBackPressedDispatcherOwner.current
                    BackHandlerBridge(activity = this@MainActivity, dispatcherOwner = owner)
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        ConfigGeneratorUI(
                            onRestoreFinished = {},
                            darkMode = darkMode,
                            onToggleDark = {
                                darkMode = !darkMode; prefs.edit()
                                .putBoolean(PREF_DARK_MODE, darkMode).apply()
                            }
                        )
                    }
                }
            }

            // Dismiss splash after first successful composition
            SideEffect { splashDismissed = true }
        }
    }
}

/* A tiny bridge composable that installs a back handler bound to current status */
@Composable
private fun BackHandlerBridge(
    activity: Activity,
    dispatcherOwner: androidx.activity.OnBackPressedDispatcherOwner?
) {
    var latestStatus by remember { mutableStateOf(XrayStatus.STOPPED) }
    LaunchedEffect(Unit) {
        XraySignals.events.collect { ev -> latestStatus = ev }
    }

    val dispatcher = dispatcherOwner?.onBackPressedDispatcher
    DisposableEffect(dispatcher) {
        if (dispatcher == null) return@DisposableEffect onDispose { }
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (latestStatus) {
                    XrayStatus.STOPPED -> {
                        // Nothing running: exit the task completely
                        try { activity.finishAndRemoveTask() } catch (_: Throwable) { activity.finish() } // Optional “harder” exit for older devices / OEMs that keep cached processes:
                    }
                    else -> {
                        // Keep service alive; just background the app
                        activity.moveTaskToBack(true)
                    }
                }
            }
        }
        dispatcher.addCallback(callback)
        onDispose { callback.remove() }
    }
}

/*───────────────────────────────────────────────────────────────────────────*/
private val HYPHEN_CLASS = "[\\-\\u2010\\u2011\\u2012\\u2013\\u2014]"

/* Fix some common bad translations */
private fun normalizeBrands(s: String): String =
    s.replace(Regex("(?i)\\bwire\\s*guard\\b"), "WireGuard")
        .replace(Regex("(?i)wine\\s*guard"), "WireGuard")
        .replace(Regex("(?i)\\bwine\\s*guard\\b"), "WireGuard")
        .replace(Regex("(?i)\\bcsxray\\b"), "CSXRAY")
        .replace(Regex("(?i)\\bCSXRAR\\b"), "CSXRAY")
        .replace("\\n", "\n")

/* Put the current WireGuard config name in the instructions */
private fun injectConfigName(raw: String, wgSuffix: String): String {
    val rx = Regex("(?i)\\bcs${HYPHEN_CLASS}?xxx\\b")
    return raw.replace(rx, wgSuffix)
}

private fun localizeStep2(tr: (String) -> String, wgSuffix: String): String {
    val s2p = tr("step2p")
    if (s2p.isNotBlank() && s2p.contains("%s")) {
        return normalizeBrands(String.format(s2p, wgSuffix))
    }
    val s2 = tr("step2")
    return normalizeBrands(injectConfigName(s2, wgSuffix))
}

/*───────────────────────────────────────────────────────────────────────────*/
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigGeneratorUI(
    onRestoreFinished: () -> Unit = {},
    darkMode: Boolean = false,
    onToggleDark: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    // language + strings
    val stringsAll = remember { loadStrings(context) }
    val langs = remember {
        listOf(
            LangOption("en", "English", "flags/us.png"),
            LangOption("ru", "Русский", "flags/ru.png"),
            LangOption("zh", "中文",     "flags/cn.png"),
            LangOption("ja", "日本語",   "flags/jp.png"),
            LangOption("sv", "Svenska", "flags/se.png"),
            LangOption("fa", "فارسی",   "flags/ir.png"),
            LangOption("ar", "العربية", "flags/sa.png")
        )
    }

    var langCode by remember {
        mutableStateOf(
            prefs.getString(
                PREF_KEY_LANG,
                if (stringsAll.containsKey("en")) "en" else stringsAll.keys.first()
            ) ?: "en"
        )
    }
    val strings = stringsAll[langCode] ?: emptyMap()
    fun tr(key: String) = strings[key] ?: (stringsAll["en"]?.get(key)).orEmpty()

    val longNameThreshold = 26

    fun defaultServers(): List<ServerInfo> = listOf(
        ServerInfo("Austria", "94.198.41.237", "2001:ac8:29:a1::f", "flags/at.png", "austria"),
        ServerInfo("Belgium", "37.120.236.13", "2001:ac8:27:103::f", "flags/be.png", "belgium"),
        ServerInfo("Brazil", "177.54.145.132", "2804:391c:0:7::70", "flags/br.png", "brazil"),
        ServerInfo("Canada - Montreal", "176.113.74.20", "2a0d:5600:19:5::f", "flags/ca.png", "montreal"),
        ServerInfo("Canada - Vancouver", "196.240.79.164", "2a02:5740:24:45::f", "flags/ca.png", "vancouver"),
        ServerInfo("Czech Republic", "217.138.220.245", "2001:ac8:33:77::f", "flags/cz.png", "czech"),
        ServerInfo("England - London", "78.129.248.68", "2001:1b40:5000:a2::f", "flags/gb-eng.png", "london"),
        ServerInfo("England - Manchester", "195.12.48.173", "2001:ac8:8b:61::f", "flags/gb-eng.png", "manchester"),
        ServerInfo("Finland", "83.143.242.44", "2a0d:5600:142:11::f", "flags/fi.png", "finland"),
        ServerInfo("France", "212.83.166.61", "2001:bc8:32d7:200c::f", "flags/fr.png", "paris"),
        ServerInfo("Germany - Berlin", "37.120.217.76", "2001:ac8:36:61::f", "flags/de.png", "berlin"),
        ServerInfo("Germany - Dusseldorf", "89.163.221.235", "2001:4ba0:ffed:76::f", "flags/de.png", "dusseldorf"),
        ServerInfo("Germany - Frankfurt", "146.70.82.4", "2a0d:5600:1d:9::f", "flags/de.png", "frankfurt"),
        ServerInfo("Hungary", "86.106.74.221", "2001:ac8:26:61::f", "flags/hu.png", "hungary"),
        ServerInfo("India", "165.231.253.164", "2001:470:1f29:204::f", "flags/in.png", "india"),
        ServerInfo("Italy - Milan", "217.138.219.220", "2001:ac8:24:a1::f", "flags/it.png", "milan"),
        ServerInfo("Moldova", "176.123.4.232", "2001:678:6d4:5023::f", "flags/md.png", "moldova"),
        ServerInfo("Netherlands", "185.107.80.86", "2a00:1768:6001:8::f", "flags/nl.png", "netherlands"),
        ServerInfo("Norway", "91.219.215.229", "2001:ac8:38:94::f", "flags/no.png", "norway"),
        ServerInfo("Poland", "37.120.211.93", "2a0d:5600:13:71::f", "flags/pl.png", "poland"),
        ServerInfo("Portugal", "91.205.230.226", "2a06:3040::ec4", "flags/pt.png", "portugal"),
        ServerInfo("Romania", "146.70.66.228", "2a04:9dc0:0:162::f", "flags/ro.png", "romania"),
        ServerInfo("Serbia", "37.120.193.221", "2001:ac8:7d:47::f", "flags/rs.png", "serbia"),
        ServerInfo("Singapore", "37.120.151.12", "2a0d:5600:1f:7::f", "flags/sg.png", "singapore"),
        ServerInfo("South Korea", "108.181.50.219", "2406:4f40:4:c::f", "flags/kr.png", "sk"),
        ServerInfo("Spain - Barcelona", "37.120.142.117", "2001:ac8:35:17::f", "flags/es.png", "barcelona"),
        ServerInfo("Sweden", "128.127.104.138", "2a00:7142:1:1::f", "flags/se.png", "sweden"),
        ServerInfo("Switzerland", "190.211.255.228", "2a02:29b8:dc01:2220::f", "flags/ch.png", "switzerland"),
        ServerInfo("Sydney - Australia", "37.120.234.252", "2001:ac8:84:4d::f", "flags/au.png", "sydney"),
        ServerInfo("Japan - Tokyo", "146.70.31.44", "2001:ac8:40:df::f", "flags/jp.png", "tokyo"),
        ServerInfo("US - California - Los Angeles", "195.206.104.204", "2a0d:5600:4f:5::f", "flags/us.png", "la"),
        ServerInfo("US - D.C. - Washington", "162.210.192.215", "2604:9a00:2010:a0bb:6::f", "flags/us.png", "dc"),
        ServerInfo("US - Florida - Miami", "146.70.240.204", "2a0d:5600:6:123::f", "flags/us.png", "florida"),
        ServerInfo("US - Georgia - Atlanta", "130.195.212.212", "2a0d:5600:145:5::f", "flags/us.png", "atlanta"),
        ServerInfo("US - Illinois - Chicago", "195.242.212.132", "2604:6600:2700:b::f", "flags/us.png", "chicago"),
        ServerInfo("US - Las Vegas - Nevada", "79.110.53.52", "2a0d:5600:3:19::f", "flags/us.png", "vegas"),
        ServerInfo("US - New York - New York City", "146.70.154.68", "2a0d:5600:24:54::f", "flags/us.png", "newyork"),
        ServerInfo("US - Oregon - Roseburg", "179.61.223.48", "2605:6c80:5:d::f", "flags/us.png", "oregon"),
        ServerInfo("US - Texas - Dallas", "209.58.150.202", "2606:9880:2100:a006:3::f", "flags/us.png", "dallas"),
        ServerInfo("US - Washington - Seattle", "108.62.5.174", "2607:f5b2:1:a00b:b::f", "flags/us.png", "seattle"),
    )

    // Mutable list so remote updates recompose UI
    val serverList = remember { mutableStateListOf<ServerInfo>().apply { addAll(defaultServers()) } }
    var selectedServer by remember { mutableStateOf<ServerInfo?>(null) }
    var showPicker by remember { mutableStateOf(false) }


    // status
    var status by remember { mutableStateOf(XrayStatus.STOPPED) }
    // ensure we print the “[restore] …” line only once
    val didRestoreOnce = remember { mutableStateOf(false) }

    // Helper: is our FG service alive? (own-app only; works on O+)
    fun isServiceRunning(): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            am.getRunningServices(Int.MAX_VALUE)
                .any { it.service.className == CSXrayService::class.java.name }
        } catch (_: Exception) { false }
    }
    // ui state
    val cm = remember { context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    var ipv6Available  by remember { mutableStateOf(hasGlobalIPv6(cm)) }
    var ipVersion      by remember { mutableStateOf(prefs.getInt(PREF_KEY_IPVER, if (ipv6Available) 6 else 4)) }
    var port           by remember { mutableStateOf(prefs.getString(PREF_KEY_PORT, "443") ?: "443") }
    // Keep separate remembered values for v4/v6 so switching is seamless
    var customIpV4     by remember { mutableStateOf(prefs.getString(PREF_CUSTOM_IP_V4, "") ?: "") }
    var customIpV6     by remember { mutableStateOf(prefs.getString(PREF_CUSTOM_IP_V6, "") ?: "") }
    var customIp       by remember { mutableStateOf(if (ipVersion == 6) customIpV6 else customIpV4) }
    val logOutput      = remember { mutableStateOf("") }
    val isRunning      = remember { mutableStateOf(false) }
    val scope          = rememberCoroutineScope()
    val activeUpdateJob = remember { mutableStateOf<Job?>(null) }
    val tailAccessJob  = remember { mutableStateOf<Job?>(null) }
    val tailErrorJob   = remember { mutableStateOf<Job?>(null) }
    val scrollState    = rememberScrollState()
    val detectWGJob    = remember { mutableStateOf<Job?>(null) }
    val lastWGTrafficAt = remember { mutableStateOf(0L) }
    var showLogs       by remember { mutableStateOf(prefs.getBoolean(PREF_KEY_SHOW_LOGS, false)) }
    var showLangSheet  by remember { mutableStateOf(false) }
    var showStopConfirm by remember { mutableStateOf(false) }
    var bootstrapPopup by remember { mutableStateOf<String?>(null) }
    var isStarting by remember { mutableStateOf(false) }
    val rxBytes = remember { mutableStateOf(0L) }
    val txBytes = remember { mutableStateOf(0L) }
    val showTrafficBadge = remember { mutableStateOf(false) }
    // Promote from stats traffic only once per run
    val promotedFromStatsOnce = remember { mutableStateOf(false) }
    // Config editor UI state
    var showConfigEditor by remember { mutableStateOf(false) }

    /** Returns a human-readable reason why IPv6 is not considered usable.
     *  If IPv6 *is* usable, returns null.
     */
    fun ipv6DebugReason(cm: ConnectivityManager): String? {
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue

            // Check transports of interest
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) continue

            val lp = cm.getLinkProperties(net) ?: continue

            // IPv6 address check
            val hasGlobalAddr = lp.linkAddresses.any { la ->
                val a = la.address
                a is Inet6Address &&
                        !a.isLinkLocalAddress &&
                        !a.isLoopbackAddress &&
                        !a.isAnyLocalAddress
            }
            if (!hasGlobalAddr) return "no global IPv6 address"

            // INTERNET capability missing
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                return "no INTERNET capability"

            // VALIDATED missing → Android says this network can't reach the internet
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
                return "network not validated (no IPv6 reachability)"

            // Default IPv6 route missing
            val hasDefaultV6Route = lp.routes.any { r ->
                r.isDefaultRoute && (r.destination.address is Inet6Address)
            }
            if (!hasDefaultV6Route) return "no default IPv6 route"

            // If we got here, this network looks good.
            return null
        }

        return "no suitable network (Wi-Fi/Cellular/Ethernet) found"
    }

    // If IPv6 disappears while we're idle, force UI + prefs back to IPv4.
    // This prevents getting stuck on a previously-selected v6 mode.
    LaunchedEffect(ipv6Available) {
        if (!ipv6Available && ipVersion == 6 && !isRunning.value) {

            val reason = ipv6DebugReason(cm) ?: "unknown reason"

            logOutput.value += "[net] IPv6 unavailable: $reason\n"
            logOutput.value += "[net] switching to IPv4\n"

            ipVersion = 4
            prefs.edit().putInt(PREF_KEY_IPVER, 4).apply()

            if (selectedServer == null) {
                customIp = customIpV4
            }
        }
    }

    // ───────── App update prompt UI state ─────────
    var showUpdateDialog by remember { mutableStateOf(false) }
    var latestAppVer by remember { mutableStateOf<String?>(null) }
    fun announceUpdateIfNewer(latestVer: String) {
        // Dedupe using the same key we set only AFTER user presses OK
        val myVer = getMyVersionName(context)
        val alreadyNotified = prefs.getString(PREF_APP_VER_NOTIFIED, null)
        if (latestVer == myVer || latestVer == alreadyNotified) return
        latestAppVer = latestVer
        showUpdateDialog = true
    }

    // One-shot: if the checker stored a newer version, show the dialog here (UI layer).
    LaunchedEffect(Unit) {
        val remote = prefs.getString(PREF_LATEST_REMOTE_APP_VER, null)
        val myVer  = getMyVersionName(context)
        val lastNotified = prefs.getString(PREF_APP_VER_NOTIFIED, null)
        if (!remote.isNullOrBlank() && remote != myVer && remote != lastNotified) {
            announceUpdateIfNewer(remote)
        }
    }

    var customCfgText by rememberSaveable {
        mutableStateOf(prefs.getString(PREF_CUSTOM_CFG, "") ?: "")
    }
    // Watchdog counters (simple debounce for down transitions only)
    val downStrikes = remember { mutableStateOf(0) }     // ACTIVE/LISTENING -> STOPPED demotions
    // Pick a slug for updater URLs even if the user hasn’t picked yet.
    fun currentSlug(): String =
        (selectedServer?.wgSlug ?: serverList.firstOrNull()?.wgSlug ?: "austria")

    // Ensure only one updater run at a time.
    val updateMutex = remember { Mutex() }
    // Keep a handle to the periodic updater loop.
    val periodicJob = remember { mutableStateOf<Job?>(null) }

    /* `tail -f`-ish */
    fun tailFile(
        scope: CoroutineScope,
        file: File,
        pollMs: Long = 350L,
        maxChunk: Int = 64 * 1024,
        onLine: (String) -> Unit
    ): Job = scope.launch(Dispatchers.IO) {
        var raf = RandomAccessFile(file, "r")
        var pointer = file.length()
        val carry = StringBuilder()

        fun reopen() {
            try { raf.close() } catch (_: Throwable) {}
            raf = RandomAccessFile(file, "r")
        }

        while (isActive) {
            val length = file.length()
            if (length < pointer) { pointer = length; reopen() }
            else if (length > pointer) {
                val toRead = min(maxChunk.toLong(), length - pointer).toInt()
                raf.seek(pointer)
                val buf = ByteArray(toRead)
                val read = raf.read(buf)
                if (read > 0) {
                    pointer += read
                    carry.append(String(buf, 0, read))
                    while (true) {
                        val idx = carry.indexOf("\n")
                        if (idx < 0) break
                        val line = carry.substring(0, idx).trimEnd('\r')
                        if (line.isNotEmpty()) onLine(line)
                        carry.delete(0, idx + 1)
                    }
                }
            }
            delay(pollMs)
        }
        try { raf.close() } catch (_: Throwable) {}
    }

    // Tailer helpers
    fun startTailersIfNeeded() {
        if (!showLogs || !isRunning.value) return
        val accessLogFile = File(context.filesDir, "access.log")
        val errorLogFile  = File(context.filesDir, "error.log")
        if (tailErrorJob.value == null || tailErrorJob.value?.isActive != true) {
            tailErrorJob.value = tailFile(scope, errorLogFile) { line -> logOutput.value += "$line\n" }
        }
        if (tailAccessJob.value == null || tailAccessJob.value?.isActive != true) {
            tailAccessJob.value = tailFile(scope, accessLogFile) { line -> logOutput.value += "$line\n" }
        }
    }

    fun stopTailers() {
        tailAccessJob.value?.cancel(); tailAccessJob.value = null
        tailErrorJob.value?.cancel();  tailErrorJob.value  = null
    }



    // ── Helper to run the updater with outcome logging ──
    fun tryUpdateServers(reason: String, force: Boolean = false) {
        // Coalesce: if an update is already running, skip starting another
        if (activeUpdateJob.value?.isActive == true) {
            logOutput.value += "[update] already running; skip ($reason)\n"
            return
        }
        activeUpdateJob.value = scope.launch {
            var completedNormally = false
            updateMutex.withLock {
                if (selectedServer == null) {
                    logOutput.value += "[update] SKIP: custom IP selected; updates disabled\n"
                    return@withLock
                }

                // If we're not green yet, at least seed the next-check window once
                // so the periodic loop has something to count down to.
                if (status != XrayStatus.ACTIVE) {
                    val now = System.currentTimeMillis()
                    var nextAt = prefs.getLong(PREF_NEXT_AT, 0L)
                    if (nextAt <= 0L) {
                        nextAt = scheduleNextCheckMs(prefs, now)
                        val remaining = (nextAt - now).coerceAtLeast(0L)
                        logOutput.value += "[update] next check in ~${minutesCeil(remaining)}m (waiting for traffic)\n"
                    }
                    return@withLock
                }

                val now   = System.currentTimeMillis()
                val last  = prefs.getLong(PREF_LAST_CHECK, 0L)
                var nextAt = prefs.getLong(PREF_NEXT_AT, 0L)

                val isFirstEver = (last == 0L)
                val isDue = force || isFirstEver || (now >= nextAt)

                if (!isDue) {
                    return@withLock
                }

                // We will actually run a check now.
                val slug = currentSlug()
                val host = "$slug.cstorm.is"
                logOutput.value += "[update] checking server list ($reason)…\n"
                logOutput.value += "[update] fetching via $host\n"

                try {
                    var latestRemoteVer: String? = prefs.getString(PREF_LATEST_REMOTE_APP_VER, null)

                    // Bypass any inner throttle (we already decided it’s due).
                    val (result, details) = checkAndUpdateServersIfNeeded(
                        context = context,
                        prefs = prefs,
                        serverList = serverList,
                        currentSelection = selectedServer,
                        force = true
                    )

                    when (result) {
                        UpdateResult.NO_CHANGE ->
                            logOutput.value += "[update] NO_CHANGE (hash match)\n"
                        UpdateResult.UPDATED ->
                            logOutput.value += "[update] UPDATED${if (!details.isNullOrBlank()) " ($details)" else ""}\n"
                        UpdateResult.NETWORK_ERROR ->
                            logOutput.value += "[update] NETWORK_ERROR (${details ?: "unknown"})\n"
                        else -> { /* ignore */ }
                    }

                    // Read the freshly stored version *after* the checker finishes.
                    prefs.getString(PREF_LATEST_REMOTE_APP_VER, null)?.let { ver ->
                        val currentVer = getMyVersionName(context)
                        if (currentVer.isNotBlank() && ver.isNotBlank() && ver != currentVer) {
                            logOutput.value += "[update] New app version available: $ver (current $currentVer)\n"
                            withContext(Dispatchers.Main) { announceUpdateIfNewer(ver) }
                        }
                    }
                    // Mark success so we schedule next-at and persist last-check
                    completedNormally = true
                } catch (ce: CancellationException) {
                    logOutput.value += "[update] cancelled\n"
                    return@withLock
                } catch (t: Throwable) {
                    logOutput.value += "[update] ERROR: ${t::class.java.simpleName}: ${t.message}\n"
                } finally {
                    // Only record + schedule if the update actually finished (not cancelled)
                    if (completedNormally) {
                        prefs.edit().putLong(PREF_LAST_CHECK, now).apply()
                        nextAt = scheduleNextCheckMs(prefs, now)
                        val remaining = (nextAt - now).coerceAtLeast(0L)
                        logOutput.value += "[update] next check in ~${minutesCeil(remaining)}m\n"
                    } else {
                        logOutput.value += "[update] skipping reschedule due to cancellation\n"
                    }
                }
            }
        }
    }

    // Hidden detector: promote to ACTIVE when dokodemo-door (wireguard-in*) sees traffic
    // and keep monitoring so we can demote when idle then re-promote on new traffic.
    fun startWGDetector(accessLogFile: File) {
        if (detectWGJob.value?.isActive == true) return

        // Warm-scan: if access.log was updated recently and contains wireguard-in*,
        // promote immediately (covers the "user swiped app, reopened later" case).
        try {
            if (accessLogFile.exists()) {
                val fresh = System.currentTimeMillis() - accessLogFile.lastModified() < 90_000L
                if (fresh) {
                    val raf = RandomAccessFile(accessLogFile, "r")
                    val len = raf.length()
                    val start = kotlin.math.max(0L, len - 64 * 1024L)
                    raf.seek(start)
                    val buf = ByteArray((len - start).toInt())
                    raf.readFully(buf)
                    raf.close()
                    val tail = String(buf)
                    if (tail.contains("wireguard-in4") || tail.contains("wireguard-in6")) {
                        lastWGTrafficAt.value = System.currentTimeMillis()
                        prefs.edit().putLong(PREF_LAST_WG_AT, lastWGTrafficAt.value).apply()
                        if (status != XrayStatus.ACTIVE) {
                            status = XrayStatus.ACTIVE
                            logOutput.value += "[wg] recent dokodemo activity; promoting to ACTIVE\n"
                            // Do NOT force; respect hourly throttle except on first-ever run.
                            tryUpdateServers(reason = "first-traffic", force = false)
                        }
                    }
                }
            }
        } catch (_: Throwable) { /* best-effort */ }

        detectWGJob.value = tailFile(scope, accessLogFile) { line ->
            // Relaxed match: any mention of our inbound tags.
            if (line.contains("wireguard-in4") || line.contains("wireguard-in6")) {
                lastWGTrafficAt.value = System.currentTimeMillis()
                prefs.edit().putLong(PREF_LAST_WG_AT, lastWGTrafficAt.value).apply()
                if (status != XrayStatus.ACTIVE) {
                    status = XrayStatus.ACTIVE
                    logOutput.value += "[wg] traffic seen on dokodemo-door; promoting to ACTIVE\n"
                    // Respect throttle except right after app process start (handled inside).
                    tryUpdateServers(reason = "first-traffic", force = false)
                }
            }
        }
    }

    // A tiny helper to cancel the periodic task eagerly (used on stop).
    fun cancelPeriodic() {
        periodicJob.value?.cancel(); periodicJob.value = null
    }

    // Load cached server list if present; else seed cache from defaults
    LaunchedEffect(Unit) {
        if (didRestoreOnce.value) return@LaunchedEffect
        val cacheFile = File(context.filesDir, CACHE_FILE_SERVERS)
        val loaded = runCatching { if (cacheFile.exists()) parseServerListJson(cacheFile.readText()) else null }.getOrNull()
        if (!loaded.isNullOrEmpty()) {
            serverList.clear(); serverList.addAll(loaded)
        } else {
            val json = toServerListJson(serverList.toList())
            cacheFile.writeText(json)
            prefs.edit().putString(PREF_HASH, sha256Hex(json)).apply()
        }
        // Restore previously selected slug if present
        val savedSlug = prefs.getString(PREF_KEY_SLUG, null)
        val savedIsCustom = prefs.getBoolean(PREF_IS_CUSTOM, false)
        selectedServer = when {
            savedIsCustom -> null
            !savedSlug.isNullOrBlank() -> serverList.firstOrNull { it.wgSlug == savedSlug } ?: serverList.firstOrNull()
            else -> serverList.firstOrNull()
        }
        if (savedIsCustom) {
            // Migrate old single-value key into per-family buckets once, if present
            val legacy = prefs.getString(PREF_CUSTOM_IP, null)
            if (!legacy.isNullOrBlank()) {
                if (prefs.getString(PREF_CUSTOM_IP_V4, null).isNullOrBlank())
                    prefs.edit().putString(PREF_CUSTOM_IP_V4, legacy).apply()
                if (prefs.getString(PREF_CUSTOM_IP_V6, null).isNullOrBlank())
                    prefs.edit().putString(PREF_CUSTOM_IP_V6, legacy).apply()
                // keep legacy key around harmlessly; no need to delete
            }
            // Set visible value according to current IP family
            customIpV4 = prefs.getString(PREF_CUSTOM_IP_V4, "") ?: ""
            customIpV6 = prefs.getString(PREF_CUSTOM_IP_V6, "") ?: ""
            customIp   = if (ipVersion == 6) customIpV6 else customIpV4
        }

        // ── Probe if service/Xray are already running (e.g., after task swipe) ──
        // Small delay so the process finishes cold-start setup before probing.
        delay(150)
        if (isServiceRunning()) {
            isRunning.value = true
            // Promote to ACTIVE immediately if we saw WG traffic recently (across process restarts)
            val now = System.currentTimeMillis()
            val lastSeen = prefs.getLong(PREF_LAST_WG_AT, 0L)
            val recentWindow = WG_IDLE_MS + 15_000L
            if (lastSeen != 0L && now - lastSeen <= recentWindow) {
                status = XrayStatus.ACTIVE
                lastWGTrafficAt.value = lastSeen
                tryUpdateServers(reason = "restore-recent", force = false)
            } else {
                status = XrayStatus.LISTENING
            }
            // If user wanted logs, reattach tailers now
            if (showLogs) startTailersIfNeeded()
            // Begin hidden WG detector so we can promote when traffic arrives.
            startWGDetector(File(context.filesDir, "access.log"))
            // Show something helpful in the log on restore
            logOutput.value += "[restore] service alive; status=${status}\n"
        } else {
            isRunning.value = false
            status = XrayStatus.STOPPED
            logOutput.value += "[restore] service not running\n"
        }
        didRestoreOnce.value = true
        onRestoreFinished()
    }


    /* react to network changes so IPv6 toggle is accurate even while a VPN exists */
    LaunchedEffect(Unit) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network)         { ipv6Available = hasGlobalIPv6(cm) }
            override fun onLost(network: Network)              { ipv6Available = hasGlobalIPv6(cm) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                ipv6Available = hasGlobalIPv6(cm)
            }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                ipv6Available = hasGlobalIPv6(cm)
            }
        }

        var registered = false
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                cm.registerDefaultNetworkCallback(cb)
            } else {
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(req, cb)
            }
            registered = true
        } catch (_: Exception) {
            // best-effort
        }

        try { kotlinx.coroutines.awaitCancellation() }
        finally { if (registered) runCatching { cm.unregisterNetworkCallback(cb) } }
    }

    LaunchedEffect(isRunning.value, status) {
        // Only poll when our app enabled the API this session
        val apiEnabledByApp = prefs.getBoolean(PREF_API_ENABLED_BY_APP, false)
        if (!isRunning.value || !apiEnabledByApp) {
            showTrafficBadge.value = false
            return@LaunchedEffect
        }

        val port = prefs.getInt(PREF_API_PORT, -1)
        if (port <= 0) { showTrafficBadge.value = false; return@LaunchedEffect }

        val client = XrayStatsClient("127.0.0.1", port)
        try {
            withContext(Dispatchers.IO) { client.start() }
            showTrafficBadge.value = false

            // Cumulative counters we read (down+up across v4+v6)
            val names = listOf(
                "inbound>>>wireguard-in4>>>traffic>>>downlink",
                "inbound>>>wireguard-in6>>>traffic>>>downlink",
                "inbound>>>wireguard-in4>>>traffic>>>uplink",
                "inbound>>>wireguard-in6>>>traffic>>>uplink"
            )

            // Delta detector state (locals inside the coroutine)
            var prevDown = -1L
            var prevUp   = -1L
            var seenStatsOnce = false
            // Ignore the very first non-zero (cumulative) snapshot for ~2s
            val warmupUntil = System.currentTimeMillis() + 1800L
            var promotedFromStats = false

            while (isActive && isRunning.value) {
                val (down, up) = withContext(Dispatchers.IO) {
                    val vals = names.map { client.getStat(it) ?: 0L }
                    (vals[0] + vals[1]) to (vals[2] + vals[3])
                }

                // First stats attachment; cancel log tailer once we know API works
                if (!seenStatsOnce) {
                    seenStatsOnce = true
                    withContext(Dispatchers.Main) {
                        detectWGJob.value?.cancel()
                        detectWGJob.value = null
                    }
                }

                // Update UI counters (raw cumulative)
                rxBytes.value = down
                txBytes.value = up
                if (!showTrafficBadge.value && (down > 0L || up > 0L)) {
                    showTrafficBadge.value = true
                }

                // Compute deltas; protect against reset (service restart) or first sample
                val deltaDown = if (prevDown >= 0) down - prevDown else 0L
                val deltaUp   = if (prevUp   >= 0) up   - prevUp   else 0L
                prevDown = down
                prevUp   = up

                // If counters jumped backwards (restart), treat as baseline only
                val saneDeltaDown = if (deltaDown < 0) 0L else deltaDown
                val saneDeltaUp   = if (deltaUp   < 0) 0L else deltaUp
                val now = System.currentTimeMillis()
                val hasNewTraffic = (saneDeltaDown > 0L || saneDeltaUp > 0L) && now >= warmupUntil

                if (hasNewTraffic) {
                    // Refresh "last traffic seen" so the idle demoter has a true clock
                    lastWGTrafficAt.value = now
                    prefs.edit().putLong(PREF_LAST_WG_AT, now).apply()

                    // Promote to ACTIVE on the first *real* delta
                    if (!promotedFromStats && status != XrayStatus.ACTIVE) {
                        promotedFromStats = true
                        status = XrayStatus.ACTIVE
                        logOutput.value += "[stats] traffic seen; promoting to ACTIVE\n"
                        tryUpdateServers(reason = "first-traffic", force = false)
                    }
                }

                delay(2000)
            }
        } finally {
            withContext(Dispatchers.IO) { client.stop() }
        }
    }


    LaunchedEffect(Unit) {
        XraySignals.events.distinctUntilChanged().collectLatest { ev ->
            when (ev) {
                XrayStatus.STOPPED -> {
                    // Tear down immediately and mark STOPPED.
                    cancelPeriodic()
                    activeUpdateJob.value?.cancel(); activeUpdateJob.value = null
                    stopTailers()
                    status = XrayStatus.STOPPED
                }
                XrayStatus.ACTIVE -> {
                    // Ignore early ACTIVE from dialer/bootstrap; only honor after real WG traffic.
                    val now = System.currentTimeMillis()
                    val recent = (lastWGTrafficAt.value != 0L) &&
                        (now - lastWGTrafficAt.value <= 30_000L)
                    val hasRealTraffic = promotedFromStatsOnce.value || recent
                    if (isRunning.value && isServiceRunning() && hasRealTraffic) {
                        status = XrayStatus.ACTIVE
                    } // else: stay LISTENING
                }
                else -> {
                    // Only accept non-STOPPED (e.g., LISTENING) when we were STOPPED,
                    // the UI is running, and the service is actually alive.
                    if (status == XrayStatus.STOPPED && isRunning.value && isServiceRunning()) {
                        status = XrayStatus.LISTENING
                    }
                }
            }
        }
    }

    // Demote back to LISTENING after a quiet window so green only shows with recent WG traffic.
    LaunchedEffect(isRunning.value) {
        if (!isRunning.value) return@LaunchedEffect
        while (isActive && isRunning.value) {
            if (status == XrayStatus.ACTIVE) {
                val idleFor = System.currentTimeMillis() - lastWGTrafficAt.value
                if (idleFor > WG_IDLE_MS) {
                    status = XrayStatus.LISTENING
                    logOutput.value += "[wg] idle >${WG_IDLE_MS/1000}s; back to LISTENING\n"
                }
            }
            delay(1_000)
        }
    }

    // ── Self-healing watchdog: converge UI to reality under odd user flows ──
    LaunchedEffect(isRunning.value) {
        if (!isRunning.value) {
            downStrikes.value = 0
            return@LaunchedEffect
        }
        downStrikes.value = 0
        while (isActive && isRunning.value) {
            val alive = isServiceRunning()

            if (!alive) {
                downStrikes.value += 1
                if (downStrikes.value >= 2) {
                    // Service is gone; update UI decisively.
                    isRunning.value = false
                    if (status != XrayStatus.STOPPED) {
                        status = XrayStatus.STOPPED
                        logOutput.value += "[watchdog] service not running; marking STOPPED\n"
                    }
                    break
                }
            }
            // When LISTENING we check more often; once ACTIVE we just watch the service existence.
            delay(if (status == XrayStatus.LISTENING) 900 else 2500)
        }
    }


    LaunchedEffect(status, selectedServer) {
        if (status == XrayStatus.ACTIVE && selectedServer != null) {
            if (periodicJob.value?.isActive != true) {
                periodicJob.value = scope.launch {
                    // Read-only: do NOT schedule here; let tryUpdateServers() own PREF_NEXT_AT.
                    while (isActive && status == XrayStatus.ACTIVE && selectedServer != null) {
                        val nowLoop = System.currentTimeMillis()
                        var nextAtPref = prefs.getLong(PREF_NEXT_AT, 0L)

                        // If we don't have a scheduled time yet, nudge the seeding path.
                        if (nextAtPref <= nowLoop) {
                            // Will log a “next check … (waiting for traffic)” line and set PREF_NEXT_AT once.
                            tryUpdateServers(reason = "seed", force = false)
                            delay(1500)
                            continue
                        }

                        // Wait until due (abort quickly if stopped)
                        while (isActive &&
                            status == XrayStatus.ACTIVE &&
                            selectedServer != null &&
                            System.currentTimeMillis() < nextAtPref) {
                            delay(1000)
                        }
                        if (!isActive || status != XrayStatus.ACTIVE || selectedServer == null) break

                        // Run the update (this will reschedule PREF_NEXT_AT and log “next check …”)
                        tryUpdateServers(reason = "periodic", force = false)

                        // Refresh from prefs for the next loop
                        nextAtPref = prefs.getLong(PREF_NEXT_AT, 0L)
                    }
                }
            }
        } else {
            periodicJob.value?.cancel()
            periodicJob.value = null
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // server picker
    if (showPicker) {
        ModalBottomSheet(onDismissRequest = { showPicker = false }, sheetState = sheetState) {
            Text(normalizeBrands(tr("choose_server")), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Spacer(Modifier.height(4.dp))

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 520.dp)
            ) {
                itemsIndexed(
                    items = serverList,
                    span = { index, _ ->
                        val name = serverList[index].name
                        if (name.length >= longNameThreshold) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                    }
                ) { index, _ ->
                    val server = serverList[index]
                    ServerChip(server = server) {
                        selectedServer = server
                        prefs.edit().putBoolean(PREF_IS_CUSTOM, false).apply()
                        prefs.edit().putString(PREF_KEY_SLUG, server.wgSlug).apply()
                        customIp = ""
                        showPicker = false
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ElevatedCard(
                        onClick = {
                            selectedServer = null
                            prefs.edit().putBoolean(PREF_IS_CUSTOM, true).apply()
                            showPicker = false
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        ) {
                            Spacer(Modifier.width(4.dp))
                            Text(normalizeBrands(tr("custom_ip")), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    // language picker
    if (showLangSheet) {
        ModalBottomSheet(onDismissRequest = { showLangSheet = false }, sheetState = sheetState) {
            Text(normalizeBrands(tr("choose_language")), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Spacer(Modifier.height(8.dp))
            val langListState = rememberLazyListState()
            LazyColumn(
                state = langListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f) // allow drag-to-scroll on landscape
            ) {
                items(langs) { opt ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                langCode = opt.code
                                prefs.edit().putString(PREF_KEY_LANG, opt.code).apply()
                                showLangSheet = false
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        AssetFlag(opt.flagAsset, 22.dp, "${opt.display} flag", Modifier.padding(end = 12.dp))
                        Text(opt.display, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.weight(1f))
                        if (langCode == opt.code) Text("✓")
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }



    // layout
    val outerScroll = rememberScrollState()
    Column(
        Modifier.padding(16.dp).fillMaxWidth().verticalScroll(outerScroll)
    ) {
        // Top row: gear (left), sun/moon (center), language flag (right)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { showConfigEditor = true }) {
                Icon(Icons.Filled.Settings, contentDescription = "Xray config")
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onToggleDark) {
                // Use brightness icons as a compatible sun/moon pair:
                // Brightness7 ≈ sun (tap to go light), Brightness4 ≈ moon (tap to go dark)
                if (darkMode) {
                    Icon(Icons.Filled.WbSunny, contentDescription = "Light mode")
                } else {
                    Icon(Icons.Filled.Bedtime, contentDescription = "Dark mode")
                }
            }
            Spacer(Modifier.weight(1f))
            val curLang = langs.find { it.code == langCode }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { showLangSheet = true }
            ) {
                AssetFlag(curLang?.flagAsset ?: "flags/us.png", 22.dp, "Language")
            }
        }

        // server
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(normalizeBrands(tr("select_server")), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            if (showTrafficBadge.value) {
                TrafficBadge(rx = rxBytes.value, tx = txBytes.value)
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { if (!isRunning.value) showPicker = true },
            enabled = !isRunning.value,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectedServer != null) {
                    AssetFlag(selectedServer!!.flagAsset, 20.dp, "${selectedServer!!.name} flag", Modifier.padding(end = 8.dp))
                    Text(selectedServer!!.name)
                } else {
                    Text(normalizeBrands(tr("custom_ip")))
                }
            }
        }

        // custom IP field (if custom)
        if (selectedServer == null) {
            Spacer(Modifier.height(8.dp))
            val label = if (ipVersion == 6)
                normalizeBrands(tr("custom_ip_v6_label"))
            else
                normalizeBrands(tr("custom_ip_v4_label"))
            OutlinedTextField(
                value = customIp,
                onValueChange = {
                    val v = it.trim()
                    customIp = v
                    if (ipVersion == 6) {
                        customIpV6 = v
                        prefs.edit().putString(PREF_CUSTOM_IP_V6, v).apply()
                    } else {
                        customIpV4 = v
                        prefs.edit().putString(PREF_CUSTOM_IP_V4, v).apply()
                    }
                },
                label = { Text(label) },
                singleLine = true,
                enabled = !isRunning.value,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(12.dp))

        // tip + port
        Text(normalizeBrands(tr("tip")), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = port,
            onValueChange = {
                val v = it.filter(Char::isDigit)
                port = v
                prefs.edit().putString(PREF_KEY_PORT, v).apply()
            },
            label = { Text(normalizeBrands(tr("port"))) },
            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = KeyboardType.Number),
            singleLine = true,
            enabled = !isRunning.value,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        // IP version
        Text(normalizeBrands(tr("ip_version")), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.padding(vertical = 8.dp)) {
            Row(Modifier.padding(end = 16.dp)) {
                RadioButton(
                    selected = ipVersion == 6,
                    onClick = {
                        if (ipv6Available && !isRunning.value) {
                            ipVersion = 6
                            prefs.edit().putInt(PREF_KEY_IPVER, 6).apply()
                            if (selectedServer == null) {
                                // swap the visible value to the remembered IPv6 entry
                                customIp = customIpV6
                            }
                        }
                    },
                    enabled = ipv6Available && !isRunning.value
                )
                Text(if (ipv6Available) normalizeBrands(tr("ipv6_rec")) else normalizeBrands(tr("ipv6_unavail")))
            }
            Row {
                RadioButton(
                    selected = ipVersion == 4,
                    onClick = {
                        if (!isRunning.value) {
                            ipVersion = 4
                            prefs.edit().putInt(PREF_KEY_IPVER, 4).apply()
                            if (selectedServer == null) {
                                // swap the visible value to the remembered IPv4 entry
                                customIp = customIpV4
                            }
                        }
                    },
                    enabled = !isRunning.value
                )
                Text(normalizeBrands(tr("ipv4")))
            }
        }

        Spacer(Modifier.height(12.dp))

        // status (compute label+color here to avoid passing function refs)
        val (statusLabel, statusColor) = when (status) {
            XrayStatus.STOPPED   -> normalizeBrands(tr("status_off"))  to Color(0xFFEF4444)
            XrayStatus.LISTENING -> normalizeBrands(tr("status_wait")) to Color(0xFF3B82F6)
            XrayStatus.ACTIVE    -> normalizeBrands(tr("status_on"))   to Color(0xFF22C55E)
        }
        StatusIndicator(label = statusLabel, color = statusColor)

        Spacer(Modifier.height(12.dp))

        // start/stop
        Button(
            onClick = {
                if (isStarting) return@Button  // debounce
                if (!isRunning.value) {
                    // START
                    scope.launch {
                        try {
                            // Reset any stale "recent traffic" markers so we don't flash green
                            lastWGTrafficAt.value = 0L
                            prefs.edit().putLong(PREF_LAST_WG_AT, 0L).apply()
                            val remotePort = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: run {
                                Toast.makeText(context, normalizeBrands(tr("invalid_port")), Toast.LENGTH_SHORT).show()
                                return@launch
                            }

                            // Ensure we aren't bound to a VPN network before starting Xray dials
                            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                            try {
                                if (Build.VERSION.SDK_INT >= 23) {
                                    cm.bindProcessToNetwork(null)
                                } else {
                                    @Suppress("DEPRECATION")
                                    ConnectivityManager.setProcessDefaultNetwork(null)
                                }
                            } catch (_: Exception) {}

                            val selectedAddress: String = if (selectedServer == null) {
                                val ok = if (ipVersion == 6) isValidIPv6(customIp) else isValidIPv4(customIp)
                                if (!ok) {
                                    Toast.makeText(
                                        context,
                                        normalizeBrands(if (ipVersion == 6) tr("invalid_ip_v6") else tr("invalid_ip_v4")),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@launch
                                }
                                customIp
                            } else {
                                if (ipVersion == 6) selectedServer!!.ipv6 else selectedServer!!.ipv4
                            }
                            isStarting = true
                            // Decide if we auto-enable API this run (only when NOT using a custom pasted config)
                            val apiEnabledByApp = !(selectedServer == null && customCfgText.isNotBlank())

                            // Randomize API port each run for obscurity (only if we're enabling it)
                            val apiPort = if (apiEnabledByApp) (20000..39999).random() else -1
                            prefs.edit()
                                .putInt(PREF_API_PORT, apiPort)
                                .putBoolean(PREF_API_ENABLED_BY_APP, apiEnabledByApp)
                                .apply()
                            // helpful local trace
                            logOutput.value += "[net] ipv6Available=$ipv6Available ipVersion=$ipVersion\n"

                            val configFile    = File(context.filesDir, "config.json")
                            val accessLogFile = File(context.filesDir, "access.log")
                            val errorLogFile  = File(context.filesDir, "error.log")

                            // Build config: default OR custom (when Custom IP is selected and enabled)
                            val defaultJson = buildDefaultConfigJson(
                                accessLogPath = accessLogFile.absolutePath,
                                errorLogPath = errorLogFile.absolutePath,
                                selectedAddress = selectedAddress,
                                remotePort = remotePort,
                                apiPort = apiPort
                            )
                            val configJson =
                                if (selectedServer == null && customCfgText.isNotBlank()) {
                                    // Resolve placeholders, but otherwise respect whatever they pasted.
                                    customCfgText
                                        .replace("%CUSTOM_IP%", selectedAddress)
                                        .replace("%CUSTOM_PORT%", remotePort.toString())
                                } else {
                                    defaultJson
                                }

                            // ── Preflight JSON (only at START). If bad, surface and abort.
                            val (ok, err) = validateJsonPretty(configJson)
                            if (!ok) {
                                val msg = err ?: "Invalid JSON"
                                if (showLogs) appendBootstrapErrorUI(context, logOutput, msg)
                                bootstrapPopup = msg   // shows the dialog below even when logs are off
                                isStarting = false
                                return@launch
                            }

                            configFile.writeText(configJson)
                            accessLogFile.writeText("")
                            errorLogFile.writeText("")

                            // Fresh run: forget any prior “recent WG traffic” so we don't flash green.
                            promotedFromStatsOnce.value = false
                            lastWGTrafficAt.value = 0L
                            prefs.edit().putLong(PREF_LAST_WG_AT, 0L).apply()

                            // follow logs if user wants them
                            if (showLogs) {
                                tailErrorJob.value  = tailFile(scope, errorLogFile) { line -> logOutput.value += "$line\n" }
                                tailAccessJob.value = tailFile(scope, accessLogFile) { line -> logOutput.value += "$line\n" }
                            }
                            // Start hidden WG detector only when API is not enabled by the app.
                            // (If API comes up later, we'll stop any running detector in the stats loop.)
                            if (!apiEnabledByApp) {
                                startWGDetector(accessLogFile)
                            }

                            // Start foreground service with explicit START action
                            ContextCompat.startForegroundService(
                                context,
                                Intent(context, CSXrayService::class.java).setAction(ACTION_START)
                            )
                            // Show immediate feedback while the service spins up
                            status = XrayStatus.LISTENING
                            isRunning.value = true
                            Toast.makeText(context, normalizeBrands(tr("xray_started")), Toast.LENGTH_SHORT).show()
                            isStarting = false
                        } catch (e: Exception) {
                            logOutput.value += "[error] ${e.message}\n"
                            isStarting = false
                        }
                    }
                } else {
                    if (isRunning.value) {
                        showStopConfirm = true
                    } else {
                        stopXray(
                            isRunning, logOutput, context, ::normalizeBrands, ::tr,
                            activeUpdateJob = activeUpdateJob,
                            tailAccessJob = tailAccessJob,
                            tailErrorJob = tailErrorJob,
                            detectWGJob = detectWGJob,
                            setStatus = { status = it }
                        )
                    }
                }
            },
            enabled = !(isStarting),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    isRunning.value && status != XrayStatus.ACTIVE -> "Stop"
                    isRunning.value -> normalizeBrands(tr("stop"))
                    isStarting -> "Starting…"
                    else -> normalizeBrands(tr("start"))
                }
            )
        }

        // ───────── App Update Dialog ─────────
        if (showUpdateDialog && latestAppVer != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showUpdateDialog = false },
                title = { Text(normalizeBrands(tr("update_available_title"))) },
                text = {
                    Column {
                        Text(
                            normalizeBrands(
                                tr("update_available_body")
                                    .replace("%s", latestAppVer!!)
                            )
                        )
                        // Show the URL on its own line for readability
                        Text(LATEST_APK_URL, style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        // Remember we’ve notified for this version.
                        latestAppVer?.let { prefs.edit().putString(PREF_APP_VER_NOTIFIED, it).apply() }
                        showUpdateDialog = false
                    }) {
                        Text(normalizeBrands(tr("ok")))
                    }
                }
            )
        }


        Spacer(Modifier.height(8.dp))

        // copy endpoint
        Button(
            onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Endpoint", "127.0.0.1:$ENDPOINT_PORT"))
                val toastText = normalizeBrands(tr("copied_endpoint")).ifBlank { "Copied: 127.0.0.1:$ENDPOINT_PORT" }
                Toast.makeText(context, toastText, Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(normalizeBrands(tr("copy_endpoint"))) }

        Spacer(Modifier.height(12.dp))

        // logs
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = showLogs,
                onCheckedChange = {
                    showLogs = it
                    prefs.edit().putBoolean(PREF_KEY_SHOW_LOGS, it).apply()
                    if (it) startTailersIfNeeded() else stopTailers()
                }
            )
            Text(normalizeBrands(tr("show_logs")))
            Spacer(Modifier.weight(1f))
            if (showLogs) {
                Text(
                    normalizeBrands(tr("copy_log")),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            val cm =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("Xray Log", logOutput.value))
                            Toast.makeText(
                                context,
                                normalizeBrands(tr("log_copied")),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .padding(4.dp)
                )
            }
        }

        if (showLogs) {
            LaunchedEffect(logOutput.value) {
                delay(10)
                scrollState.scrollTo(scrollState.maxValue)
            }
            Spacer(Modifier.height(6.dp))
            Text(normalizeBrands(tr("xray_output")), style = MaterialTheme.typography.titleSmall)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .verticalScroll(scrollState)
                    .padding(8.dp)
            ) { Text(logOutput.value) }
        } else {
            Spacer(Modifier.height(8.dp))
            val wgSuffix = selectedServer?.wgSlug?.let { "cs-$it" } ?: normalizeBrands(tr("applicable"))
            val step1 = normalizeBrands(tr("step1"))
            val step2 = localizeStep2(::tr, wgSuffix)
            val step3 = normalizeBrands(tr("step3"))
            val step4 = normalizeBrands(tr("step4"))
            val step5 = normalizeBrands(tr("step5"))
            val step6 = normalizeBrands(tr("step6"))

            val instrScroll = rememberScrollState()

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)   // keep card compact
                        .verticalScroll(instrScroll)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(normalizeBrands(tr("what_next")), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(step1)
                    Text(step2)
                    Text(step3)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        Text(step4, fontWeight = FontWeight.Normal) // keep non-bold
                    }
                    Text(step5)
                    Text(step6)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Config Editor "window"
    if (showConfigEditor) {
        // Generate a preview/config payload depending on selection.
        val accessLogFile = File(context.filesDir, "access.log")
        val errorLogFile  = File(context.filesDir, "error.log")
        val remotePortPreview = port.toIntOrNull() ?: 443
        val previewAddress = when {
            selectedServer == null -> "%CUSTOM_IP%"
            ipVersion == 6 -> selectedServer!!.ipv6
            else -> selectedServer!!.ipv4
        }
        val previewPort = if (selectedServer == null) "%CUSTOM_PORT%" else remotePortPreview.toString()

        val templateJson = buildDefaultConfigJson(
            accessLogPath = accessLogFile.absolutePath,
            errorLogPath = errorLogFile.absolutePath,
            selectedAddress = previewAddress,
            remotePort = previewPort
        )

        val isEditable = (selectedServer == null)
        var editorText by remember(showConfigEditor, selectedServer, customCfgText) {
            mutableStateOf(if (isEditable) {
                if (customCfgText.isNotBlank()) customCfgText else templateJson
            } else {
                // For predefined servers, show read-only of the current default config
                templateJson
            })
        }

        ModalBottomSheet(
            onDismissRequest = { showConfigEditor = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.9f)   // keep actions reachable on small screens
                    .padding(16.dp)
            ) {
                item {
                    Text(
                        if (isEditable) normalizeBrands(tr("custom_cfg_title"))
                        else normalizeBrands(tr("current_cfg_title")),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    if (isEditable) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = cm.primaryClip
                                val pasted = clip?.getItemAt(0)?.coerceToText(context)?.toString()
                                if (!pasted.isNullOrBlank()) {
                                    editorText = pasted
                                } else {
                                    Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                }
                            }) { Text(normalizeBrands(tr("paste"))) }
                            TextButton(onClick = { editorText = templateJson }) { Text(normalizeBrands(tr("reset"))) }
                        }
                    } else {
                        Text(
                            normalizeBrands(tr("editor_tip")),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    CodeEditor(
                        value = editorText,
                        onValueChange = {
                            editorText = it
                            if (isEditable) {
                                customCfgText = it
                            }
                        },
                        readOnly = !isEditable,
                        // Bound height so the inner verticalScroll in CodeEditor has finite constraints
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 260.dp, max = 560.dp)
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showConfigEditor = false }) { Text(normalizeBrands(tr("close"))) }
                        if (isEditable) {
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                // Blind save; no validation here
                                customCfgText = editorText
                                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                                showConfigEditor = false
                            }) { Text(normalizeBrands(tr("save"))) }
                        }
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }

    // stop confirmation
    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text(normalizeBrands(tr("stop_confirm_title"))) },
            text = { Text(normalizeBrands(tr("stop_confirm_body"))) },
            confirmButton = {
                TextButton(onClick = {
                    showStopConfirm = false
                    tailAccessJob.value?.cancel(); tailAccessJob.value = null
                    tailErrorJob.value?.cancel();  tailErrorJob.value  = null
                    stopXray(
                        isRunning, logOutput, context, ::normalizeBrands, ::tr,
                        activeUpdateJob = activeUpdateJob,
                        tailAccessJob = tailAccessJob,
                        tailErrorJob = tailErrorJob,
                        detectWGJob = detectWGJob,
                        setStatus = { status = it }
                    )
                }) { Text(normalizeBrands(tr("stop"))) }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) { Text(normalizeBrands(tr("cancel"))) }
            }
        )
    }

    // Bootstrap JSON error popup (when logs are OFF)
    if (bootstrapPopup != null) {
        AlertDialog(
            onDismissRequest = { bootstrapPopup = null },
            title = { Text(normalizeBrands(tr("json_error_title"))) },
            text = {
                Surface(
                    tonalElevation = 2.dp,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        bootstrapPopup!!,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(12.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { bootstrapPopup = null }) { Text(normalizeBrands(tr("ok"))) }
            }
        )
    }
}

/*───────────────────────────────────────────────────────────────────────────*/
private fun stopXray(
    isRunning: MutableState<Boolean>,
    logOutput: MutableState<String>,
    context: Context,
    norm: (String) -> String,
    tr: (String) -> String,
    activeUpdateJob: MutableState<Job?>? = null,
    tailAccessJob: MutableState<Job?>? = null,
    tailErrorJob: MutableState<Job?>? = null,
    detectWGJob: MutableState<Job?>? = null,
    setStatus: ((XrayStatus) -> Unit)? = null
) {
    // Only send STOP to the service if it's actually running; otherwise don't try
    // to "start a service just to stop it" (this can crash on O+).
    if (isServiceProcessRunning(context)) {
        try {
            ContextCompat.startForegroundService(
                context, Intent(context, CSXrayService::class.java).setAction(ACTION_STOP)
            )
        } catch (_: Exception) { /* ignore */ }
    } else {
        // Best-effort: ask framework to stop if a stale instance exists.
        runCatching { context.stopService(Intent(context, CSXrayService::class.java)) }
    }
    // Cancel update + tailers immediately so logs go quiet upon stop
    try { activeUpdateJob?.value?.cancel() } catch (_: Throwable) {}
    if (activeUpdateJob != null) activeUpdateJob.value = null
    try { tailAccessJob?.value?.cancel() } catch (_: Throwable) {}
    if (tailAccessJob != null) tailAccessJob.value = null
    try { tailErrorJob?.value?.cancel() } catch (_: Throwable) {}
    if (tailErrorJob != null) tailErrorJob.value = null
    try { detectWGJob?.value?.cancel() } catch (_: Throwable) {}
    if (detectWGJob != null) detectWGJob.value = null

    // Update UI immediately
    isRunning.value = false
    setStatus?.invoke(XrayStatus.STOPPED)   // deterministic UI even if a late LISTENING arrives
    // Clear the "recent WG traffic" marker so we don't incorrectly show green on next cold start
    runCatching {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(PREF_LAST_WG_AT, 0L).apply()
    }
    XraySignals.notifyStopped()
    logOutput.value = "[jni] Xray stop requested (service)\n"
    Toast.makeText(context, norm(tr("xray_stopped")), Toast.LENGTH_SHORT).show()
}

@Composable
private fun TrafficBadge(rx: Long, tx: Long) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.padding(start = 8.dp)
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("RX ${humanBytes(rx)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(10.dp))
            Text("TX ${humanBytes(tx)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
    }
}

/* Status chip visual only */
@Composable
private fun StatusIndicator(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(color = color, shape = RoundedCornerShape(6.dp))
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/* Grid chip for a server */
@Composable
private fun ServerChip(server: ServerInfo, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            AssetFlag(server.flagAsset, 22.dp, "${server.name} flag", Modifier.padding(end = 10.dp))
            Text(server.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
    }
}

/* Load a flag from assets */
@Composable
private fun AssetFlag(
    path: String,
    size: Dp,
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val bitmap: ImageBitmap? = remember(path) {
        runCatching {
            context.assets.open(path).use { input ->
                BitmapFactory.decodeStream(input)?.asImageBitmap()
            }
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(painter = BitmapPainter(bitmap), contentDescription = contentDescription, modifier = modifier.size(size))
    } else {
        Spacer(modifier = modifier.size(size))
    }
}

// Build the default config JSON the app would use internally.
// `remotePort` can be Int or "%CUSTOM_PORT%" (we accept String for preview convenience).
private fun buildDefaultConfigJson(
    accessLogPath: String,
    errorLogPath: String,
    selectedAddress: String,
    remotePort: Any,
    apiPort: Int = -1
): String {
    val accessEsc = accessLogPath.replace("\\", "\\\\")
    val errorEsc = errorLogPath.replace("\\", "\\\\")
    val portStr = remotePort.toString()
    val statsBlock = if (apiPort > 0) """
  , "stats": {},
    "policy": {
      "system": {
        "statsInboundUplink": true,
        "statsInboundDownlink": true,
        "statsOutboundUplink": true,
        "statsOutboundDownlink": true
      }
    },
    "api": { "tag": "api", "services": ["StatsService"] }
""".trimIndent() else ""

    val apiInboundAndRule = if (apiPort > 0) """
  ,{
     "listen": "127.0.0.1",
     "port": $apiPort,
     "protocol": "dokodemo-door",
     "settings": { "address": "127.0.0.1" },
     "tag": "api-in"
   }
""".trimIndent() else ""

    val apiRoute = if (apiPort > 0) """
  ,{ "type": "field", "inboundTag": ["api-in"], "outboundTag": "api" }
""".trimIndent() else ""

    return """
{
  "log": { "access": "$accessEsc", "error": "$errorEsc", "loglevel": "debug" },
  "inbounds": [{
      "tag": "local-socks4", "protocol": "socks", "listen": "127.0.0.1", "port": 10808,
      "settings": { "udp": false, "auth": "noauth", "udpTimeout": 60 }
    },{
      "tag": "local-socks6", "protocol": "socks", "listen": "::1", "port": 10808,
      "settings": { "udp": false, "auth": "noauth", "udpTimeout": 60 }
    },{
      "listen": "127.0.0.1", "port": $ENDPOINT_PORT, "protocol": "dokodemo-door",
      "settings": { "address": "$selectedAddress", "port": $portStr, "network": "udp", "udpTimeout": 60 },
      "tag": "wireguard-in4"
    },{
      "listen": "::1", "port": $ENDPOINT_PORT, "protocol": "dokodemo-door",
      "settings": { "address": "$selectedAddress", "port": $portStr, "network": "udp", "udpTimeout": 60 },
      "tag": "wireguard-in6"
    }$apiInboundAndRule],
  "outbounds": [{
      "protocol": "vless", "settings": { "vnext": [{
        "address": "$selectedAddress", "port": $portStr,
        "users": [{ "id": "8020ebb3-7ec9-4bf2-a5a3-ee13f3c480e9", "encryption": "none" }]
      }]},
      "streamSettings": { "network": "tcp", "security": "reality",
        "realitySettings": { "serverName": "cloudflare.com", "fingerprint": "chrome",
          "shortId": "8a12df734e", "spiderX": "/", "publicKey": "jVcTjg6A1chgD2MFD7wjLwMO6UIDCowW1QfbusF5khE" } },
      "tag": "reality-out"
    }],
  "routing": { "rules": [
      { "type": "field", "inboundTag": ["wireguard-in4"], "outboundTag": "reality-out" },
      { "type": "field", "inboundTag": ["wireguard-in6"], "outboundTag": "reality-out" },
      { "type": "field", "inboundTag": ["local-socks4"],  "outboundTag": "reality-out" },
      { "type": "field", "inboundTag": ["local-socks6"],  "outboundTag": "reality-out" }$apiRoute
    ]}$statsBlock
}
""".trimIndent()
}

/* Basic IP validators */
private val ipv4Pattern: Pattern = Pattern.compile(
    "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$"
)
private val ipv6Pattern: Pattern = Pattern.compile(
    "^(([0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}|(([0-9A-Fa-f]{1,4}:){1,7}:)|(([0-9A-Fa-f]{1,4}:){1,6}:[0-9A-Fa-f]{1,4})|(([0-9A-Fa-f]{1,4}:){1,5}(:[0-9A-Fa-f]{1,4}){1,2})|(([0-9A-Fa-f]{1,4}:){1,4}(:[0-9A-Fa-f]{1,4}){1,3})|(([0-9A-Fa-f]{1,4}:){1,3}(:[0-9A-Fa-f]{1,4}){1,4})|(([0-9A-Fa-f]{1,4}:){1,2}(:[0-9A-Fa-f]{1,4}){1,5})|([0-9A-Fa-f]{1,4}:((:[0-9A-Fa-f]{1,4}){1,6}))|(:((:[0-9A-Fa-f]{1,4}){1,7}|:))|(fe80:(:[0-9A-Fa-f]{0,4}){0,4}%[0-9A-Za-z]{1,})|(::(ffff(:0{1,4}){0,1}:){0,1}((25[0-5]|(2[0-4]|1{0,1}[0-9])?[0-9])\\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9])?[0-9]))|(([0-9A-Fa-f]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1{0,1}[0-9])?[0-9])\\.){3,3}(25[0-5]|(2[0-4]|1{0,1}[0-9])?[0-9])))$"
)

private fun isValidIPv4(s: String): Boolean = ipv4Pattern.matcher(s).matches()
private fun isValidIPv6(s: String): Boolean = ipv6Pattern.matcher(s).matches()

/*───────────────────────────────────────────────────────────────────────────*/
/*  Server list cache + updater (throttled to 1 hour)                         */
/*───────────────────────────────────────────────────────────────────────────*/
private suspend fun checkAndUpdateServersIfNeeded(
    context: Context,
    prefs: android.content.SharedPreferences,
    serverList: MutableList<ServerInfo>,
    currentSelection: ServerInfo?,
    force: Boolean = false
): Pair<UpdateResult, String?> = withContext(Dispatchers.IO) {
    val now = System.currentTimeMillis()
    val last = prefs.getLong(PREF_LAST_CHECK, 0L)
    val remainingMs = CHECK_PERIOD_MS - (now - last)
    if (!force && last > 0L && remainingMs > 0) {
        val mins = (remainingMs / 60000L).coerceAtLeast(1L)
        return@withContext UpdateResult.THROTTLED to "${mins}m left"
    }

    // Choose the node we’re currently aimed at (or first as a sane default).
    val slug = currentSelection?.wgSlug ?: serverList.firstOrNull()?.wgSlug ?: "austria"
    val urls = makeUpdateUrlsFor(slug)

    val remoteMetaRaw = fetchTextViaSocks(
        urls.hashUrl,
        uaVer = getMyVersionName(context)
    ).getOrElse { err ->
        return@withContext UpdateResult.NETWORK_ERROR to "hash: ${err.message ?: err::class.java.simpleName}"
    }
    val remoteMeta = remoteMetaRaw.trim()
    if (remoteMeta.isEmpty()) {
        return@withContext UpdateResult.NETWORK_ERROR to "empty-remote-hash"
    }

    // Extract SHA-256 and optional version token from the meta
    val hashMatch = Regex("(?i)\\b[a-f0-9]{64}\\b").find(remoteMeta)
    val remoteHash = hashMatch?.value?.lowercase() ?: ""
    // support "version=1.2.3", "ver: 1.2.3", "app 1.2.3", etc.
    val verMatch = Regex("(?i)\\b(?:version|ver|app)\\s*[:=]?\\s*([0-9]+(?:\\.[0-9]+){1,3})\\b")
        .find(remoteMeta.replace('\n',' '))
    val latestVer = verMatch?.groupValues?.getOrNull(1) ?: ""
    prefs.edit().putString(PREF_LATEST_REMOTE_APP_VER, latestVer).apply()

    if (remoteHash.isEmpty()) {
        return@withContext UpdateResult.NETWORK_ERROR to "no-sha256-in-meta"
    }

    // Fast path: if our last-known good hash matches, skip without touching disk
    val prefHash = prefs.getString(PREF_HASH, null)
    if (!force && prefHash != null && prefHash.equals(remoteHash, ignoreCase = true)) {
        prefs.edit().putLong(PREF_LAST_CHECK, now).apply()
        return@withContext UpdateResult.NO_CHANGE to null
    }

    val cacheFile = File(context.filesDir, CACHE_FILE_SERVERS)
    val localJson = if (cacheFile.exists()) cacheFile.readText() else toServerListJson(serverList)
    val localHash = sha256Hex(localJson)

    // If the on-disk content already matches the advertised hash, treat as no-change
    if (remoteHash.equals(localHash, ignoreCase = true)) {
        // Also sync PREF_HASH so future checks can short-circuit
        prefs.edit().putString(PREF_HASH, remoteHash).putLong(PREF_LAST_CHECK, now).apply()
        return@withContext UpdateResult.NO_CHANGE to null
    }

    if (remoteHash.isNotEmpty() && remoteHash != localHash) {
        val listJson = fetchTextViaSocks(
            urls.listUrl,
            uaVer = getMyVersionName(context)
        ).getOrElse { err ->
            return@withContext UpdateResult.NETWORK_ERROR to "list: ${err.message ?: err::class.java.simpleName}"
        }
        // Integrity check: fetched JSON must match the advertised hash
        val bodyHash = sha256Hex(listJson)
        if (!bodyHash.equals(remoteHash, ignoreCase = true)) {
            return@withContext UpdateResult.NETWORK_ERROR to "hash-mismatch"
        }
        val parsed = parseServerListJson(listJson)
        if (parsed.isNotEmpty()) {
            // Integrity check: fetched JSON must match the advertised hash
            val bodyHash = sha256Hex(listJson)
            if (!bodyHash.equals(remoteHash, ignoreCase = true)) {
                return@withContext UpdateResult.NETWORK_ERROR to "hash-mismatch"
            }
            writeTextAtomic(cacheFile, listJson)
            prefs.edit()
                .putString(PREF_HASH, remoteHash)
                .putLong(PREF_LAST_CHECK, now)
                .apply()

            withContext(Dispatchers.Main) {
                val oldSlug = currentSelection?.wgSlug
                serverList.clear()
                serverList.addAll(parsed)
                // keep selection if present
                oldSlug?.let { slug ->
                    serverList.firstOrNull { it.wgSlug == slug } ?: run { /* leave as-is */ }
                }
            }
            return@withContext UpdateResult.UPDATED to "${parsed.size} items"
        }
    }

    // No change (hash matched or parsed empty)
    prefs.edit().putLong(PREF_LAST_CHECK, now).apply()
    return@withContext UpdateResult.NO_CHANGE to null
}

private fun parseServerListJson(json: String): List<ServerInfo> {
    val arr = JSONArray(json)
    val out = ArrayList<ServerInfo>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val name = o.optString("name")
        val ipv4 = o.optString("ipv4")
        val ipv6 = o.optString("ipv6")
        val wgSlug = o.optString("wgSlug")
        val flag = o.optString("flagAsset", "flags/us.png")
        if (name.isNotEmpty() && ipv4.isNotEmpty() && ipv6.isNotEmpty() && wgSlug.isNotEmpty()) {
            out += ServerInfo(name, ipv4, ipv6, flag, wgSlug)
        }
    }
    return out
}

private fun toServerListJson(list: List<ServerInfo>): String {
    val arr = JSONArray()
    list.forEach {
        arr.put(
            JSONObject().apply {
                put("name", it.name)
                put("ipv4", it.ipv4)
                put("ipv6", it.ipv6)
                put("flagAsset", it.flagAsset)
                put("wgSlug", it.wgSlug)
            }
        )
    }
    return arr.toString()
}

private fun sha256Hex(s: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    val b = md.digest(s.toByteArray())
    return b.joinToString("") { "%02x".format(it) }
}

/** Atomic write to avoid partial files on crashes/kill during write. */
private fun writeTextAtomic(target: File, content: String) {
    val dir = target.parentFile ?: return target.writeText(content)
    if (!dir.exists()) dir.mkdirs()
    val tmp = File.createTempFile(target.name, ".tmp", dir)
    try {
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            // Fallback if rename fails (some filesystems)
            target.writeText(content)
            tmp.delete()
        }
    } catch (t: Throwable) {
        // Best-effort cleanup
        runCatching { tmp.delete() }
        throw t
    }
}

/** Best-effort check whether our foreground service is alive. */
private fun isServiceProcessRunning(context: Context): Boolean = try {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    @Suppress("DEPRECATION")
    am.getRunningServices(Int.MAX_VALUE)
        .any { it.service.className == CSXrayService::class.java.name }
    } catch (_: Throwable) { false }

/** GET over HTTPS via local SOCKS; returns Result<String> (body). */
private fun fetchTextViaSocks(
    urlStr: String,
    timeoutMs: Int = 8000,
    uaVer: String = "0"
): Result<String> {
    return runCatching {
        val url = URL(urlStr)
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(SOCKS_HOST, SOCKS_PORT))
        val conn = (url.openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "csxray/$uaVer")
            setRequestProperty("Accept", "text/plain, application/json")
            setRequestProperty("Connection", "close")
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code in 200..299) body
        else throw RuntimeException("HTTP $code from ${url.host}${url.path}${if (body.isNotBlank()) ": $body" else ""}")
    }
}