package com.spizganed.quickbuds.ui

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.spizganed.quickbuds.bluetooth.BudsConnectionManager
import com.spizganed.quickbuds.bluetooth.PacketLogger
import com.spizganed.quickbuds.protocol.Capabilities
import com.spizganed.quickbuds.protocol.ModelCatalog
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The problem report, filled by [ReportActivity]: JSON to the report Worker (report-worker/), or the Google Form
 * (`formResponse`, no sign-in) when the Worker is unreachable. The desktop app does the same (desktop/src/report.rs).
 * A changed form question gets a new id.
 */
object ProblemReport {

    private const val WORKER = "https://quickbuds-report.spizganed.workers.dev/report"

    /** One report. [categories] are [CATEGORIES] values; [log] is null when the person turned it off. */
    class Report(val model: String, val categories: List<String>, val description: String, val log: String?, val crash: Boolean)

    private const val FORM = "https://docs.google.com/forms/d/e/1FAIpQLScPNdmogyip2EDWZayNUSW3ElYz674GAC7t7BZuFNHgvxGP_Q/formResponse"
    private const val ENTRY_MODEL = "entry.849813820"
    private const val ENTRY_CATEGORY = "entry.494675210"
    private const val ENTRY_DESCRIPTION = "entry.1786789589"
    private const val ENTRY_LOG = "entry.863055756"

    /** Google answers 413 somewhere between 31 and 41 KB of form body. */
    private const val MAX_BODY = 30_000

    /** The form's Category options, in the form's own words (it rejects any other value). */
    val CATEGORIES = listOf("UI", "Lag", "Connection", "Feature not working", "Battery", "Other")

    /** The log's first line: phone, Android, QuickBuds, buds model and firmware. Exported logs start with it too. */
    fun header(c: Context): String {
        val p = c.getSharedPreferences(ThemeRes.PREFS_NAME, Context.MODE_PRIVATE)
        val model = ModelCatalog.current(c)?.name ?: p.getString(ModelCatalog.KEY_DEVICE_NAME, null) ?: "unknown buds"
        val id = p.getString(Capabilities.KEY_PRODUCT_ID, null) ?: "?"
        val fw = p.getString(BudsConnectionManager.KEY_FIRMWARE, null) ?: "?"
        return "QuickBuds ${UpdateChecker.installed(c)} | ${Build.MANUFACTURER} ${Build.MODEL} | " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | $model ($id) firmware $fw"
    }

    /**
     * The log as sent: header, the packet log, this app's logcat warnings and errors, then [crash] (a crash report,
     * or ""). Logcat shows misbehaviour the packet log misses, crash or not.
     */
    fun log(c: Context, crash: String = ""): String = scrub(c,
        header(c) + "\n" + PacketLogger.getFileContent().lines().joinToString("\n", transform = ::dropDeviceBytes) +
            appLog() + crash)

    /** [text] without Bluetooth addresses or this phone's name. */
    fun scrub(c: Context, text: String): String {
        var t = MAC.replace(text, "XX:XX:XX:XX:XX:XX")
        for (name in phoneNames(c)) t = t.replace(name, "[this phone]")
        return t
    }

    /**
     * The last 100 warnings and errors of this process, without the renderer's noise. An app reads its own logcat
     * without a permission. The crash handler calls it too: the next launch is a new process with a new log.
     */
    fun appLog(): String {
        val out = runCatching {
            ProcessBuilder("logcat", "-d", "-t", "100", "--pid=${android.os.Process.myPid()}", "HWUI:S", "*:W")
                .redirectErrorStream(true).start().inputStream.bufferedReader().readLines()
                .filterNot { it.startsWith("---------") }.joinToString("\n").trim()
        }.getOrDefault("")
        return if (out.isEmpty()) "" else "\n\nLOGCAT (warnings and errors):\n$out"
    }

    /**
     * A packet line whose bytes hold device addresses and names (PROTOCOL.md §9 device list and device manager:
     * `0x8112`, `0x0429`, `0x8132`, push `0x0204` sub `06`) keeps only its 9-byte header.
     */
    private fun dropDeviceBytes(line: String): String {
        val at = line.indexOf(": AA ")
        if (at < 0) return line
        val b = line.substring(at + 2).split(' ').mapNotNull { it.toIntOrNull(16) }
        if (b.size < 10) return line
        val cmd = b[4] or (b[5] shl 8)
        if (cmd !in DEVICE_CMDS && !(cmd == 0x0204 && b[9] == 0x06)) return line
        return line.substring(0, at + 2) + b.take(9).joinToString(" ") { "%02X".format(it) } + " [device data removed]"
    }

    private val DEVICE_CMDS = setOf(0x8112, 0x0429, 0x8132)

    private val MAC = Regex("(?i)\\b([0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b")

    /** This phone's own names: the device name and the Bluetooth name (null without the permission). */
    private fun phoneNames(c: Context): List<String> = listOfNotNull(
        runCatching { Settings.Global.getString(c.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull(),
        runCatching { c.getSystemService(BluetoothManager::class.java)?.adapter?.name }.getOrNull()
    ).filter { it.length >= 3 }.distinct()

    /**
     * The form body. A log over [MAX_BODY] loses its oldest lines (the header stays): the newest part shows the
     * problem.
     */
    private fun formBody(r: Report): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val fields = listOf(ENTRY_MODEL to r.model, ENTRY_DESCRIPTION to r.description) + r.categories.map { ENTRY_CATEGORY to it }
        val head = fields.joinToString("&") { "${it.first}=${enc(it.second)}" }
        if (r.log == null) return head
        val lines = r.log.lines()
        var from = 1
        while (true) {
            val kept = (listOf(lines[0]) + lines.drop(from)).joinToString("\n")
            val body = "$head&$ENTRY_LOG=${enc(kept)}"
            if (body.length <= MAX_BODY || from >= lines.size) return body
            // Drop a tenth of what is left: a few passes, not one per line.
            from += maxOf(1, (lines.size - from) / 10)
        }
    }

    /** [r] as the person reads it before Send: one block per field, the log last. */
    fun preview(r: Report): String = listOfNotNull(
        "Model:\n${r.model}",
        r.categories.takeIf { it.isNotEmpty() }?.let { "Category:\n" + it.joinToString(", ") },
        "Description:\n${r.description}",
        r.log?.let { "Log:\n$it" }
    ).joinToString("\n\n")

    /**
     * Posts [r] to the Worker; to the Google Form only when the Worker is unreachable (no connection, or a 5xx).
     * Network thread only. Returns (sent, the Worker's answer text when it refused the report).
     */
    fun send(c: Context, r: Report): Pair<Boolean, String?> {
        val json = JSONObject()
            .put("app", "QuickBuds ${UpdateChecker.installed(c)} Android")
            .put("model", r.model)
            .put("categories", JSONArray(r.categories))
            .put("description", r.description)
            .put("log", r.log ?: JSONObject.NULL)
            .put("crash", r.crash)
        try {
            val (code, text) = post(WORKER, "application/json; charset=UTF-8", json.toString())
            if (code < 500) {
                if (code != 200) PacketLogger.log("REPORT: worker answered $code")
                val message = runCatching { JSONObject(text).getString("message") }.getOrNull()
                return (code == 200) to message.takeIf { code != 200 }
            }
            PacketLogger.log("REPORT: worker answered $code, using the form")
        } catch (e: IOException) {
            PacketLogger.error("report worker", e)
        }
        return try {
            val (code, _) = post(FORM, "application/x-www-form-urlencoded; charset=UTF-8", formBody(r))
            if (code != 200) PacketLogger.log("REPORT: form answered $code")
            (code == 200) to null
        } catch (e: IOException) {
            PacketLogger.error("report send", e)
            false to null
        }
    }

    /** The answer code and body. Throws [IOException] without a connection. */
    private fun post(url: String, type: String, body: String): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Content-Type", type)
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } finally {
            conn.disconnect()
        }
    }
}
