package eu.advantly.gate.check

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File

/**
 * Eine Zeile des Feldtest-Logs der Uhr (`fieldtest.jsonl`, Format v1, geschrieben von
 * `wearApp/.../diagnostics/FieldTestLogic.kt`). Enthält bewusst **keine** Puls-/Schrittwerte.
 *
 * Zähler ([sent], [received], …) sind kumulativ seit Prozessstart ([pid]); Bildschirmzeiten ([onMs], [ambientMs],
 * [offMs]) gelten seit der vorherigen Zeile desselben Prozesses.
 */
data class FieldSample(
    val kind: String,
    val wallMs: Long,
    val elapsedMs: Long,
    val pid: Int,
    val battery: Int?,
    val charging: Boolean?,
    val chargeUah: Long?,
    val display: String?,
    val onMs: Long,
    val ambientMs: Long,
    val offMs: Long,
    val powerSave: Boolean?,
    val workout: Boolean,
    val workoutPhase: String?,
    val countOnly: Boolean,
    val matchId: String?,
    val sport: String?,
    val scorer: Boolean?,
    val points: Int?,
    val events: Int?,
    val pending: Int?,
    val peers: Int?,
    val link: String?,
    val sent: Int,
    val received: Int,
    val acked: Int,
    val retries: Int,
    val failed: Int,
    val model: String?,
    val os: String?,
    val app: String?,
)

/** Ergebnis des Einlesens: gültige Zeilen (nach Zeit sortiert) und Anzahl verworfener Zeilen. */
data class FieldLog(
    val samples: List<FieldSample>,
    val skippedLines: Int,
) {
    /** Gerät laut letzter `start`-Zeile. */
    val device: String?
        get() =
            samples.lastOrNull { it.model != null }?.let { s ->
                listOfNotNull(s.model, s.os?.let { "Android $it" }, s.app?.let { "App $it" }).joinToString(" · ")
            }
}

object FieldLogParser {
    /** Höchste verstandene Formatversion. */
    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(files: List<File>): FieldLog = parseLines(files.flatMap { readLines(it) })

    fun parseLines(lines: List<String>): FieldLog {
        var skipped = 0
        val samples =
            lines.mapNotNull { raw ->
                val line = raw.trim().removePrefix("")
                if (line.isEmpty()) return@mapNotNull null
                val sample = runCatching { sample(json.parseToJsonElement(line).jsonObject) }.getOrNull()
                if (sample == null) skipped++
                sample
            }
        return FieldLog(samples.sortedWith(compareBy({ it.wallMs }, { it.elapsedMs })), skipped)
    }

    private fun sample(o: JsonObject): FieldSample? {
        val version = o.int("v") ?: return null
        if (version > VERSION) return null
        return FieldSample(
            kind = o.str("kind") ?: return null,
            wallMs = o.long("wallMs") ?: return null,
            elapsedMs = o.long("elapsedMs") ?: 0,
            pid = o.int("pid") ?: 0,
            battery = o.int("battery"),
            charging = o.bool("charging"),
            chargeUah = o.long("chargeUah"),
            display = o.str("display"),
            onMs = o.long("onMs") ?: 0,
            ambientMs = o.long("ambientMs") ?: 0,
            offMs = o.long("offMs") ?: 0,
            powerSave = o.bool("powerSave"),
            workout = o.bool("workout") ?: false,
            workoutPhase = o.str("workoutPhase"),
            countOnly = o.bool("countOnly") ?: false,
            matchId = o.str("matchId"),
            sport = o.str("sport"),
            scorer = o.bool("scorer"),
            points = o.int("points"),
            events = o.int("events"),
            pending = o.int("pending"),
            peers = o.int("peers"),
            link = o.str("link"),
            sent = o.int("sent") ?: 0,
            received = o.int("received") ?: 0,
            acked = o.int("acked") ?: 0,
            retries = o.int("retries") ?: 0,
            failed = o.int("failed") ?: 0,
            model = o.str("model"),
            os = o.str("os"),
            app = o.str("app"),
        )
    }

    /** UTF-8; per PowerShell-Umleitung entstandene UTF-16-Dateien werden erkannt. */
    private fun readLines(file: File): List<String> {
        val bytes = file.readBytes()
        val utf16 =
            bytes.size >= 2 &&
                ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) || (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))
        val text = if (utf16) String(bytes, Charsets.UTF_16) else String(bytes, Charsets.UTF_8)
        return text.lines()
    }

    private fun JsonObject.prim(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

    private fun JsonObject.str(key: String): String? = prim(key)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.int(key: String): Int? = prim(key)?.intOrNull

    private fun JsonObject.long(key: String): Long? = prim(key)?.longOrNull

    private fun JsonObject.bool(key: String): Boolean? = prim(key)?.booleanOrNull
}
