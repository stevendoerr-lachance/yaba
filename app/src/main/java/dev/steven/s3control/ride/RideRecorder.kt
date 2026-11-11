package dev.steven.s3control.ride

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import dev.steven.s3control.I18n
import dev.steven.s3control.R
import dev.steven.s3control.ble.BikeController
import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ble.BikeState
import dev.steven.s3control.ble.Gatt
import dev.steven.s3control.ble.S2Frame
import dev.steven.s3control.ble.S2State
import dev.steven.s3control.ble.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The ride logger: while active it polls the bike fast and writes one CSV with samples every
 * two seconds plus every event. Built to catch what happens right before a shutdown on the road.
 *
 * Every row also carries, for an S2, the phone's GPS speed (`gps_kmh`, only while the S2 drive diagnosis has it
 * switched on, [GpsSpeed]), its error code (`s2_error`) and the whole decrypted status block (`raw_frame`). An S3
 * leaves the three empty, also with the GPS switch on, so its logs and its logger service stay as they were. These
 * columns stand before `message`, which stays the last and always quoted column; readers go by header name.
 * The message carries the event's code after its kind ([messageOf]), so a viewer colours it by meaning.
 */
class RideRecorder(
    private val context: Context,
    private val bike: BikeController,
    private val log: EventLog,
    private val scope: CoroutineScope,
) {
    data class Status(
        val active: Boolean = false,
        val startedAtMillis: Long? = null,
        val samples: Int = 0,
        val incidents: Int = 0,
        val file: File? = null,
        /** Cockpit marks written into this file (row type `mark`, find them with `grep ',mark,'`). */
        val marks: Int = 0,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private var job: Job? = null
    private var gpsJob: Job? = null
    private var writer: FileWriter? = null
    private val gps = GpsSpeed.get(context)
    private val dir: File get() = File(context.filesDir, "logs").apply { mkdirs() }

    fun start() {
        if (_status.value.active) return
        // A connectedDevice foreground service needs BLUETOOTH_CONNECT granted, or startForeground throws.
        if (Build.VERSION.SDK_INT >= 31 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            throw IllegalStateException(I18n.get(R.string.core_ride_error_permission))
        }
        // Throws (e.g. ForegroundServiceStartNotAllowedException) before anything is marked active.
        ContextCompat.startForegroundService(context, Intent(context, RideLoggerService::class.java))
        val now = System.currentTimeMillis()
        val file = File(dir, "ride-${FILE_TS.format(Instant.ofEpochMilli(now))}.csv")
        val w = FileWriter(file, true)
        // Machine-readable: stable English column names and row types, never translated.
        w.write(CSV_HEADER + "\n")
        w.flush()
        writer = w
        _status.value = Status(active = true, startedAtMillis = now, file = file)
        bike.requestPoll(POLL_OWNER, POLL_MS)
        // GPS speed only for an S2 (its drive diagnosis); the family may only become known once connected.
        gpsJob = scope.launch(Dispatchers.Main) {
            var first = true
            bike.state.map { it.family == BikeFamily.S2 }.distinctUntilChanged().collect { s2 ->
                if (s2) {
                    gps.acquire(this@RideRecorder)
                    // The service picked its type when it started; an S2 that shows up later needs it again.
                    if (!first) onGpsChanged()
                } else {
                    gps.release(this@RideRecorder)
                }
                first = false
            }
        }
        log.sink = { e ->
            val type = if (e.kind == EventLog.Kind.MARK) ROW_MARK else ROW_EVENT
            appendLine(e.atMillis, type, bike.state.value, messageOf(e))
            // A Vorfall is an alert or an error, everywhere in YABA; the marks are counted on their own.
            if (e.kind == EventLog.Kind.ALERT || e.kind == EventLog.Kind.ERROR) _status.update { it.copy(incidents = it.incidents + 1) }
            if (e.kind == EventLog.Kind.MARK) _status.update { it.copy(marks = it.marks + 1) }
        }
        log.add(EventLog.Kind.INFO, I18n.get(R.string.core_log_ride_started), file.name)
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                appendLine(System.currentTimeMillis(), ROW_SAMPLE, bike.state.value, "")
                _status.update { it.copy(samples = it.samples + 1) }
                delay(2_000)
            }
        }
    }

    fun stop() {
        if (!_status.value.active) return
        log.add(EventLog.Kind.INFO, I18n.get(R.string.core_log_ride_stopped))
        job?.cancel(); job = null
        log.sink = null
        bike.requestPoll(POLL_OWNER, null)
        gpsJob?.cancel(); gpsJob = null
        gps.release(this)
        synchronized(this) {
            runCatching { writer?.close() }
            writer = null
        }
        _status.update { it.copy(active = false) }
        context.stopService(Intent(context, RideLoggerService::class.java))
    }

    /**
     * The GPS comparison was switched on or allowed while recording: listen now, and start the service again so
     * it also holds location while the screen is off (it picks its type in onStartCommand).
     */
    fun onGpsChanged() {
        gps.update()
        if (!_status.value.active || !wantsGps) return
        runCatching { ContextCompat.startForegroundService(context, Intent(context, RideLoggerService::class.java)) }
    }

    /** The logger writes GPS speed (and its service holds location): switched on and allowed, and the bike is an S2. */
    val wantsGps: Boolean get() = gps.active && bike.state.value.family == BikeFamily.S2

    fun files(): List<File> = dir.listFiles { f -> f.extension == "csv" }?.sortedByDescending { it.lastModified() }.orEmpty()

    fun delete(file: File) {
        if (file == _status.value.file && _status.value.active) return
        file.delete()
    }

    fun shareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, I18n.get(R.string.core_ride_share_subject, file.nameWithoutExtension))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun appendLine(at: Long, type: String, s: BikeState, message: String) {
        val s2 = s2Frame(s)
        val row = listOf(
            ROW_TS.format(Instant.ofEpochMilli(at)),
            type,
            s.link.name,
            s.status?.name ?: "",
            if (s.shippingImminent) "1" else "0",
            s.lock?.name ?: "",
            s.speedKmh?.toString() ?: "",
            s.battery?.toString() ?: "",
            s.chargeState?.name ?: "",
            s.powerLevel?.toString() ?: "",
            s.buttons?.let { if (it.powerButton) "1" else "0" } ?: "",
            s.supplyOk?.let { if (it) "1" else "0" } ?: "",
            if (s.wheelMoving) "1" else "0",
            s.faults.joinToString("|"),
            s.raw[Gatt.ERRORS.short]?.toHex() ?: "",
            s.raw[Gatt.MOTOR_BATTERY_LEVEL.short]?.toHex() ?: "",
            gps.kmhAt(at)?.takeIf { s.family == BikeFamily.S2 }?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "",
            s2?.errorCode?.toString() ?: "",
            s2?.hex ?: "",
            "\"" + message.replace("\"", "'") + "\"",
        ).joinToString(",")
        synchronized(this) {
            val w = writer ?: return
            runCatching { w.write(row); w.write("\n"); w.flush() }
        }
    }

    /** The S2 status block the state holds; null for every other family. */
    private fun s2Frame(s: BikeState): S2Frame? =
        if (s.family == BikeFamily.S2) s.raw[S2State.RAW_KEY]?.let(S2Frame::parse) else null

    companion object {
        /**
         * The message column of an event or mark row: "KIND/CODE: text (detail)", the code only where the event has
         * one other than its kind ("ALERT/SUPPLY_LOW: …", "MARK: Marke 1 (…)"). RideLogReader reads it, and the older
         * "KIND: text (detail)" too.
         */
        internal fun messageOf(e: EventLog.Event): String {
            val head = e.code?.takeIf { it.name != e.kind.name }?.let { "${e.kind}/${it.name}" } ?: e.kind.name
            return "$head: ${e.message}${e.detail?.let { " ($it)" } ?: ""}"
        }

        /** The header every log starts with; RideLogGpsTest checks it against the reader's column names. */
        internal const val CSV_HEADER = "time,type,link,status,shipping_reported,lock,kmh,battery,charger,level,reset_button," +
            "supply_ok,wheel_moving,faults,faults_raw,battery_raw,gps_kmh,s2_error,raw_frame,message"
        private const val ROW_EVENT = "event"
        private const val ROW_SAMPLE = "sample"
        private const val ROW_MARK = "mark"

        /** Its claim on the poll ([BikeController.requestPoll]): every 1.5 s while recording, as always. */
        private const val POLL_OWNER = "ride-logger"
        private const val POLL_MS = 1_500L

        private val FILE_TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault())
        private val ROW_TS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }
}
