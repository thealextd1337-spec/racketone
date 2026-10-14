package eu.advantly.wear.diagnostics

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Zeitgestempeltes Feldtest-Log für Spike S5 (Datei im App-Speicher, Format siehe [DiagLine]).
 *
 * Ablage: app-spezifischer externer Speicher `/sdcard/Android/data/eu.advantly/files/diag/s5-diag.log`
 * (per `adb pull` abholbar, keine Berechtigung nötig), Fallback interner Speicher `files/diag/`.
 * Geschrieben wird auf einem eigenen Thread; jede Zeile wird sofort geflusht, damit bei einem Prozess-Kill
 * nichts Wesentliches verloren geht. Enthält Pulswerte – nur für den internen Feldtest, nie in Release-Builds übernehmen.
 */
object DiagnosticLog {
    private const val TAG = "AdvantlyS5"
    const val FILE_NAME = "s5-diag.log"
    private const val MAX_BYTES = 20L * 1024 * 1024

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "s5-diag-log").apply { isDaemon = true } }

    @Volatile
    private var file: File? = null
    private var writer: BufferedWriter? = null

    /** Einmal in `Application.onCreate` aufrufen. */
    fun init(context: Context) {
        if (file != null) return
        val dir = (context.getExternalFilesDir("diag") ?: File(context.filesDir, "diag")).apply { mkdirs() }
        file = File(dir, FILE_NAME)
    }

    val path: String?
        get() = file?.absolutePath

    fun log(
        event: String,
        vararg fields: Pair<String, Any?>,
    ) {
        val line = DiagLine.format(System.currentTimeMillis(), SystemClock.elapsedRealtime(), event, linkedMapOf(*fields))
        Log.i(TAG, line)
        executor.execute { append(listOf(line)) }
    }

    /** Mehrere Zeilen am Stück (z. B. ein Batch Puls-Samples) mit einem Flush. */
    fun logAll(lines: List<Pair<String, Map<String, Any?>>>) {
        if (lines.isEmpty()) return
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val formatted = lines.map { (event, fields) -> DiagLine.format(now, elapsed, event, fields) }
        executor.execute { append(formatted) }
    }

    private fun append(lines: List<String>) {
        val target = file ?: return
        try {
            rotateIfNeeded(target)
            val w = writer ?: BufferedWriter(FileWriter(target, true)).also { writer = it }
            lines.forEach {
                w.write(it)
                w.newLine()
            }
            w.flush()
        } catch (e: IOException) {
            Log.w(TAG, "Diagnose-Log nicht schreibbar", e)
            writer = null
        }
    }

    private fun rotateIfNeeded(target: File) {
        if (target.length() < MAX_BYTES) return
        writer?.close()
        writer = null
        target.renameTo(File(target.parentFile, "s5-diag-${System.currentTimeMillis()}.log"))
    }
}
