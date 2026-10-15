package eu.advantly.wear.diagnostics

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import androidx.core.content.edit
import eu.advantly.match.session.MatchSession
import eu.advantly.match.session.SessionListener
import eu.advantly.match.session.SessionState
import eu.advantly.wear.BuildConfig
import eu.advantly.wear.workout.WorkoutPhase
import eu.advantly.wear.workout.WorkoutStateHolder
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Feldtest-Modus der Uhr (P1-Gate, Akku/Laufzeit): schreibt etwa einmal pro Minute einen Messpunkt nach
 * `/sdcard/Android/data/eu.advantly/files/diag/fieldtest.jsonl` (Format: [FieldTestLogic]) – Akkustand, Laden,
 * Display an/Ambient/aus, Workout aktiv, Sync-Zähler, offenes Match mit Punkt-/Event-Zahl. **Keine Gesundheitsdaten.**
 *
 * - **Nur Debug-Builds** ([AVAILABLE] = `BuildConfig.DEBUG`) und nur, wenn der versteckte Schalter auf dem Startbildschirm
 *   an ist (Standard aus, gerätelokal, vom Backup ausgeschlossen). Im Release-Build gibt es weder Schalter noch Datei.
 * - Der Minutentakt läuft über den Main-Looper und weckt die Uhr **nicht** (würde den Akku-Messwert verfälschen). Im
 *   Tiefschlaf pausiert er; beim nächsten Display-Wechsel wird ein überfälliger Messpunkt nachgeholt. Die Auswertung nutzt
 *   echte Zeitstempel.
 * - Läuft im App-Prozess; nach einem Prozessneustart (Schalter an) startet es in `Application.onCreate` neu.
 *
 * Alle Aufrufe auf dem Main-Thread; geschrieben wird auf einem eigenen Thread.
 */
object FieldTestRecorder {
    /** Feldtest-Modus überhaupt verfügbar (nur Debug). */
    val AVAILABLE: Boolean = DebugToolsGate.available(BuildConfig.DEBUG)

    const val FILE_NAME = "fieldtest.jsonl"
    private const val TAG = "AdvantlyFieldTest"
    private const val PREFS = "fieldtest_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val INTERVAL_MS = 60_000L
    private const val MAX_BYTES = 10L * 1024 * 1024

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "fieldtest-log").apply { isDaemon = true } }
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private var app: Context? = null
    private var session: MatchSession? = null
    private var running = false
    private var file: File? = null
    private var display: DisplayTimeAccumulator? = null
    private var lastSession: SessionReading? = null
    private var lastSampleElapsed = 0L

    private val tick =
        object : Runnable {
            override fun run() {
                if (!running) return
                write(FieldTestLogic.KIND_TICK)
                handler.postDelayed(this, INTERVAL_MS)
            }
        }

    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit

            override fun onDisplayRemoved(displayId: Int) = Unit

            override fun onDisplayChanged(displayId: Int) {
                if (!running || displayId != Display.DEFAULT_DISPLAY) return
                val now = SystemClock.elapsedRealtime()
                display?.onChange(FieldTestLogic.displayMode(currentDisplayState()), now)
                // Nach Tiefschlaf: überfälligen Messpunkt nachholen und den Takt neu ausrichten
                if (now - lastSampleElapsed >= INTERVAL_MS) {
                    handler.removeCallbacks(tick)
                    tick.run()
                }
            }
        }

    private val sessionListener =
        SessionListener { state ->
            if (!running) return@SessionListener
            val next = read(state)
            FieldTestLogic.transitions(lastSession, next).forEach { (kind, reading) -> write(kind, reading) }
            lastSession = next
        }

    /** Einmal in `Application.onCreate` nach dem Start der Session; startet die Aufzeichnung, wenn der Schalter an ist. */
    fun install(
        context: Context,
        matchSession: MatchSession,
    ) {
        if (!AVAILABLE || app != null) return
        app = context.applicationContext
        session = matchSession
        if (isEnabled(context)) start("process_start")
    }

    fun isEnabled(context: Context): Boolean = DebugToolsGate.recording(AVAILABLE) { prefs(context).getBoolean(KEY_ENABLED, false) }

    /** Schalter des Startbildschirms (nur Debug). */
    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        if (!AVAILABLE) return
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
        if (enabled) start("enabled") else stop("disabled")
    }

    /** Pfad der Protokolldatei (sobald einmal gestartet), sonst `null`. */
    val path: String?
        get() = file?.absolutePath

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun start(reason: String) {
        val context = app ?: return
        val matchSession = session ?: return
        if (running) return
        running = true
        val dir = (context.getExternalFilesDir("diag") ?: File(context.filesDir, "diag")).apply { mkdirs() }
        file = File(dir, FILE_NAME)
        display = DisplayTimeAccumulator(FieldTestLogic.displayMode(currentDisplayState()), SystemClock.elapsedRealtime())
        context.getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, handler)
        lastSession = read(matchSession.state.value)
        write(
            FieldTestLogic.KIND_START,
            device = DeviceInfo(Build.MODEL, Build.VERSION.RELEASE, BuildConfig.VERSION_NAME),
            reason = reason,
        )
        matchSession.addListener(sessionListener)
        handler.postDelayed(tick, INTERVAL_MS)
    }

    private fun stop(reason: String) {
        if (!running) return
        write(FieldTestLogic.KIND_STOP, reason = reason)
        running = false
        handler.removeCallbacks(tick)
        session?.removeListener(sessionListener)
        app?.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        display = null
        lastSession = null
    }

    private fun write(
        kind: String,
        reading: SessionReading? = null,
        device: DeviceInfo? = null,
        reason: String? = null,
    ) {
        val context = app ?: return
        val matchSession = session ?: return
        val now = SystemClock.elapsedRealtime()
        lastSampleElapsed = now
        val times = display?.drain(now) ?: DisplayTimes(DisplayMode.UNKNOWN, 0, 0, 0)
        val sample =
            FieldTestSample(
                kind = kind,
                wallMs = System.currentTimeMillis(),
                elapsedMs = now,
                pid = Process.myPid(),
                battery = battery(context),
                display = times,
                workout = workout(),
                session = reading ?: read(matchSession.state.value),
                device = device,
                reason = reason,
            )
        val line = sample.toJsonLine()
        val target = file ?: return
        executor.execute { append(target, line) }
    }

    private fun append(
        target: File,
        line: String,
    ) {
        try {
            if (target.length() >= MAX_BYTES) target.renameTo(File(target.parentFile, "fieldtest-${System.currentTimeMillis()}.jsonl"))
            FileWriter(target, true).use {
                it.write(line)
                it.write("\n")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Feldtest-Protokoll nicht schreibbar", e)
        }
    }

    private fun read(state: SessionState): SessionReading {
        val match = state.match
        val stats = session?.linkStats
        return SessionReading(
            matchId = match?.id?.value,
            sport = match?.format?.sport?.name,
            scorer = match?.isScorer,
            points = match?.pointsPlayed,
            events = match?.eventCount,
            pending = state.sync.pendingToPeers,
            peers = state.sync.peers.size,
            link = state.sync.link.name,
            sent = stats?.framesSent ?: 0,
            received = stats?.delivered ?: 0,
            acked = stats?.acked ?: 0,
            retries = stats?.retries ?: 0,
            failed = stats?.failed ?: 0,
        )
    }

    private fun battery(context: Context): BatteryReading {
        val manager = context.getSystemService(BatteryManager::class.java)
        val percent = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        val charge = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.takeIf { it > 0 }?.toLong()
        val powerSave = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode
        return BatteryReading(percent, manager?.isCharging, charge, powerSave)
    }

    private fun workout(): WorkoutReading {
        val state = WorkoutStateHolder.state.value
        return WorkoutReading(state.phase == WorkoutPhase.ACTIVE, state.phase.name, state.countOnly)
    }

    private fun currentDisplayState(): Int =
        app
            ?.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.state ?: Display.STATE_UNKNOWN
}
