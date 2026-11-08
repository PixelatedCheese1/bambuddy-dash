package com.pixelatedcheese1.bambudash

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class PState { IDLE, PRINTING, PAUSED }
data class Status(
    val state: PState = PState.IDLE, val progress: Float = 0f,
    val archiveId: Int? = null, val awaitingClear: Boolean = false, val connected: Boolean = true,
    val remainingMin: Int = 0, val layer: Int = 0, val total: Int = 0, val nozzle: Int = 0, val bed: Int = 0,
)
data class Recent(val id: Int, val name: String, val minutes: Int)

private fun JSONObject.str(vararg k: String) =
    k.firstNotNullOfOrNull { n -> optString(n, "").takeIf { it.isNotBlank() && it != "null" } }
private fun JSONObject.num(vararg k: String) =
    k.firstNotNullOfOrNull { n -> if (has(n) && !isNull(n)) optDouble(n).takeIf { !it.isNaN() } else null }
private fun listOf(t: String, key: String): List<JSONObject> {
    val s = t.trim()
    val a = if (s.startsWith("[")) JSONArray(s) else JSONObject(s).optJSONArray(key) ?: JSONArray()
    return (0 until a.length()).map { a.getJSONObject(it) }
}

/** All Bambuddy endpoint paths live in this file. Verify them in Bambuddy's built-in API browser. */
class BambuddyApi(base: String, key: String) {
    class HttpErr(val code: Int, msg: String) : Exception("HTTP $code $msg".trim())

    // Accept "192.168.1.5:8000", "http://host:8000/", or a pasted ".../api/v1" or page URL: keep only scheme://host:port
    private val root = base.trim().let { b ->
        val u = java.net.URI(if ("://" in b) b else "http://$b")
        "${u.scheme}://${u.authority}/api/v1"
    }
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .addInterceptor { it.proceed(it.request().newBuilder().header("X-API-Key", key.trim()).build()) }
        .build()

    private suspend fun call(path: String, method: String = "GET", json: String = "{}"): String =
        withContext(Dispatchers.IO) {
            val body = if (method == "GET") null else json.toRequestBody("application/json".toMediaType())
            http.newCall(Request.Builder().url(root + path).method(method, body).build()).execute().use { r ->
                val t = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw HttpErr(r.code, "on ${r.request.url}" + if (r.code == 403) " (key lacks permission)" else "")
                t
            }
        }

    fun coverUrl(id: Int, tag: String) = "$root/printers/$id/cover?t=${tag.hashCode()}"
    fun thumbUrl(archiveId: Int) = "$root/archives/$archiveId/thumbnail"

    suspend fun printerIds(): List<Int> {
        val t = try { call("/printers") } catch (e: HttpErr) { if (e.code == 404) call("/printers/") else throw e }
        return listOf(t, "printers").map { it.getInt("id") }
    }

    suspend fun status(id: Int): Status {
        val j = JSONObject(call("/printers/$id/status"))
        val cp = j.optJSONObject("current_print")
        val raw = (j.str("state", "gcode_state", "stage", "status") ?: "").lowercase()
        val state = when {
            "pause" in raw -> PState.PAUSED
            raw in setOf("running", "prepare", "slicing") || "print" in raw -> PState.PRINTING
            else -> PState.IDLE
        }
        val archiveId = if (j.has("current_archive_id") && !j.isNull("current_archive_id")) j.getInt("current_archive_id") else null
        var rem = (j.num("remaining_time", "mc_remaining_time") ?: cp?.num("remaining_time") ?: 0.0).toInt()
        if (rem > 1440) rem /= 60 // assume seconds if implausibly large for minutes
        val t = j.optJSONObject("temperatures")
        return Status(
            state, (((j.num("progress", "mc_percent") ?: cp?.num("progress") ?: 0.0) / 100).toFloat()).coerceIn(0f, 1f),
            archiveId, j.optBoolean("awaiting_plate_clear"), j.optBoolean("connected", true), rem,
            (j.num("current_layer", "layer_num") ?: 0.0).toInt(), (j.num("total_layers", "total_layer_num") ?: 0.0).toInt(),
            (t?.num("nozzle", "nozzle_temp") ?: j.num("nozzle_temp") ?: 0.0).toInt(),
            (t?.num("bed", "bed_temp") ?: j.num("bed_temp") ?: 0.0).toInt(),
        )
    }

    suspend fun control(id: Int, cmd: String) { // cmd: pause | resume | stop
        try { call("/webhook/printer/$id/$cmd", "POST") } // shown in Bambuddy's own Webhook Endpoints list
        catch (e: HttpErr) {
            if (e.code != 404 && e.code != 405) throw e
            call("/printers/$id/print/$cmd", "POST")
        }
    }

    suspend fun recent(): List<Recent> = listOf(call("/archives?limit=15"), "archives").map {
        Recent(it.getInt("id"), (it.str("print_name", "filename") ?: "Print").removeSuffix(".3mf"),
            ((it.num("print_time_seconds") ?: 0.0) / 60).toInt())
    }.distinctBy { it.name }

    suspend fun archiveName(id: Int): String? =
        JSONObject(call("/archives/$id")).str("print_name", "filename")?.removeSuffix(".3mf")

    /** Queues the archive on the printer; the queue starts it once the printer is idle and the plate is clear. */
    suspend fun startPrint(archiveId: Int, printerId: Int, clearPlateFirst: Boolean) {
        if (clearPlateFirst) call("/printers/$printerId/clear-plate", "POST")
        call("/queue", "POST", """{"archive_id":$archiveId,"printer_id":$printerId}""")
    }
}
