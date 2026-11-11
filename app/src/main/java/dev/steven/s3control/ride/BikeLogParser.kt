package dev.steven.s3control.ride

/**
 * Parser for the bike's own internal log (the "Fahrtenschreiber"), downloaded by
 * [dev.steven.s3control.ble.BikeController.readBikeLog]. Pure and offline: give it the raw text,
 * get back structured telemetry rows plus the derate / shifter / wake / self-test / motor-error
 * events woven through the same stream.
 *
 * Line format (mainware console.c): every line is "<unix-seconds> <msg>\r\n".
 *  - Boot header, once per boot: "<epoch> TIME;LiPOSOC;BMSSOC;BATTEMP;BATVOLTAGE;BATCURRENT;
 *    MOTORCURRENT;MOTORTMP;DRIVERTMP;SPEED;ODO;BOOST;LUX;BATDSG".
 *  - Telemetry row, every 30 s while awake: "<epoch> ;<LiPOSOC>;<BMSSOC>;...;<BATDSG>".
 *    The LiPOSOC field carries a literal '%' from the firmware format string; it is tolerated.
 *  - Event lines: "Set power state to ... (Current limit: X.Y A, SOC: Z %)", "Motor error HHHH",
 *    "Diag ok" / "Diag fail", "Error gear N = M, retry", "ERR Position", "Shifter off",
 *    "MT zeropos", "Wake Reason: ...", "Wake counter N of M, type T", "BleDeepSleep soc S wakeups W".
 *
 * Sources: decomp mainware states.c:2919-3030 (header + row), battery.c:878 (power state),
 * log.c:316-333 / main.c:550 (wake), shifter.c (shifter), states.c:1885-1890 (diag).
 * Confidence: H for the format, M for 1.9.3 (the emitter was recompiled in 1.08.02).
 */
object BikeLogParser {

    /** One 30-second telemetry sample. Fields that could not be parsed are null; [valid] is false for
     *  the garbage rows the bike writes when the battery is absent (e.g. "-247.-6"). */
    data class Telemetry(
        val epoch: Long,
        val liPoSoc: Double?,
        val bmsSoc: Int?,
        val batTempC: Double?,
        val batVolt: Double?,
        val batCurrent: Double?,
        val motorCurrent: Double?,
        val motorTempC: Int?,
        val driverTempC: Int?,
        val speedKmh: Double?,
        val odoKm: Double?,
        val boost: Boolean?,
        val lux: Int?,
        val batDischarging: Int?,
        val valid: Boolean,
    )

    /** "Set power state to PWR_X (Current limit: X.Y A, SOC: Z %)" — the real derate moment the motor saw. */
    data class DerateEvent(val epoch: Long, val powerState: String, val currentLimitA: Double, val socPercent: Int)

    enum class ShifterEventKind { GEAR_RETRY, POSITION_ERROR, SHIFTER_OFF, ZERO_CAL }

    /** A line from the shifter: a retry ("Error gear N = M, retry"), a position fault, "Shifter off",
     *  or the zero-point calibration ("MT zeropos"). [gear]/[reported] are set only for GEAR_RETRY. */
    data class ShifterEvent(
        val epoch: Long,
        val kind: ShifterEventKind,
        val gear: Int?,
        val reported: Int?,
        val raw: String,
    )

    /** Why the bike woke. [counter]/[counterOf]/[type] come from the 1.9.x "Wake counter N of M, type T". */
    data class WakeEvent(
        val epoch: Long,
        val reason: String,
        val counter: Int?,
        val counterOf: Int?,
        val type: Int?,
        val raw: String,
    )

    data class MotorErrorEvent(val epoch: Long, val code: Int)

    data class SelfTestEvent(val epoch: Long, val passed: Boolean)

    data class DeepSleepEvent(val epoch: Long, val soc: Int?, val wakeups: Int?)

    data class ParsedLog(
        val telemetry: List<Telemetry>,
        val derate: List<DerateEvent>,
        val shifter: List<ShifterEvent>,
        val wake: List<WakeEvent>,
        val deepSleep: List<DeepSleepEvent>,
        val motorErrors: List<MotorErrorEvent>,
        val selfTests: List<SelfTestEvent>,
        /** Epoch of each boot header line: the seams between wake sessions. */
        val boots: List<Long>,
        /** Lines that matched no known shape (kept verbatim for the raw view). */
        val unparsed: List<String>,
    ) {
        /** Only the plausible telemetry rows (battery present), oldest first. */
        val validTelemetry: List<Telemetry> get() = telemetry.filter { it.valid }

        val span: LongRange?
            get() {
                val all = telemetry.map { it.epoch } + derate.map { it.epoch } + wake.map { it.epoch }
                val min = all.minOrNull() ?: return null
                return min..(all.maxOrNull() ?: min)
            }

        /** The telemetry speed closest in time to [epoch] (for "at which speed did this shift happen?"). */
        fun speedAt(epoch: Long): Double? =
            validTelemetry.minByOrNull { kotlin.math.abs(it.epoch - epoch) }?.speedKmh
    }

    private val HEADER = Regex("""^TIME;LiPOSOC;""")
    private val POWER = Regex("""Set power state to (\w+) \(Current limit: (-?\d+)\.(\d) A, SOC: (-?\d+) %\)""")
    private val MOTOR_ERR = Regex("""Motor error ([0-9A-Fa-f]{1,4})""")
    private val GEAR_RETRY = Regex("""Error gear (-?\d+) = (-?\d+), retry""")
    private val WAKE_REASON = Regex("""Wake Reason: (.+)""")
    private val WAKE_COUNTER = Regex("""Wake counter (\d+) of (\d+), type (\d+)""")
    private val DEEP_SLEEP = Regex("""BleDeepSleep soc (-?\d+) wakeups (\d+)""")

    fun parse(text: String): ParsedLog {
        val telemetry = ArrayList<Telemetry>()
        val derate = ArrayList<DerateEvent>()
        val shifter = ArrayList<ShifterEvent>()
        val wake = ArrayList<WakeEvent>()
        val deepSleep = ArrayList<DeepSleepEvent>()
        val motorErrors = ArrayList<MotorErrorEvent>()
        val selfTests = ArrayList<SelfTestEvent>()
        val boots = ArrayList<Long>()
        val unparsed = ArrayList<String>()

        for (rawLine in text.split('\n')) {
            val line = rawLine.trim('\r', ' ', '\u0000', '￿')
            if (line.isEmpty()) continue
            val sp = line.indexOf(' ')
            if (sp <= 0) { unparsed.add(line); continue }
            val epoch = line.substring(0, sp).toLongOrNull()
            if (epoch == null) { unparsed.add(line); continue }
            val msg = line.substring(sp + 1)

            when {
                msg.startsWith(";") -> {
                    telemetry.add(parseTelemetry(epoch, msg.substring(1)))
                }
                HEADER.containsMatchIn(msg) -> boots.add(epoch)
                msg.startsWith("Set power state to") -> {
                    val m = POWER.find(msg)
                    if (m != null) {
                        val whole = m.groupValues[2].toInt()
                        val frac = m.groupValues[3].toInt()
                        val limit = (if (whole < 0) whole - frac / 10.0 else whole + frac / 10.0)
                        derate.add(DerateEvent(epoch, m.groupValues[1], limit, m.groupValues[4].toInt()))
                    } else unparsed.add(line)
                }
                msg.startsWith("Motor error") ->
                    MOTOR_ERR.find(msg)?.let { motorErrors.add(MotorErrorEvent(epoch, it.groupValues[1].toInt(16))) }
                        ?: unparsed.add(line)
                msg.startsWith("Error gear") -> {
                    val m = GEAR_RETRY.find(msg)
                    shifter.add(
                        ShifterEvent(
                            epoch, ShifterEventKind.GEAR_RETRY,
                            m?.groupValues?.get(1)?.toIntOrNull(), m?.groupValues?.get(2)?.toIntOrNull(), msg,
                        ),
                    )
                }
                msg.startsWith("ERR Position") ->
                    shifter.add(ShifterEvent(epoch, ShifterEventKind.POSITION_ERROR, null, null, msg))
                msg.startsWith("Shifter off") ->
                    shifter.add(ShifterEvent(epoch, ShifterEventKind.SHIFTER_OFF, null, null, msg))
                msg.startsWith("MT zeropos") ->
                    shifter.add(ShifterEvent(epoch, ShifterEventKind.ZERO_CAL, null, null, msg))
                msg.startsWith("Wake Reason") ->
                    WAKE_REASON.find(msg)?.let { wake.add(WakeEvent(epoch, it.groupValues[1].trim(), null, null, null, msg)) }
                        ?: unparsed.add(line)
                msg.startsWith("Wake counter") -> {
                    val m = WAKE_COUNTER.find(msg)
                    if (m != null) {
                        wake.add(
                            WakeEvent(
                                epoch, "counter", m.groupValues[1].toInt(), m.groupValues[2].toInt(),
                                m.groupValues[3].toInt(), msg,
                            ),
                        )
                    } else unparsed.add(line)
                }
                msg.startsWith("BleDeepSleep") -> {
                    val m = DEEP_SLEEP.find(msg)
                    deepSleep.add(DeepSleepEvent(epoch, m?.groupValues?.get(1)?.toIntOrNull(), m?.groupValues?.get(2)?.toIntOrNull()))
                }
                msg.startsWith("Diag ok") -> selfTests.add(SelfTestEvent(epoch, true))
                msg.startsWith("Diag fail") -> selfTests.add(SelfTestEvent(epoch, false))
                else -> unparsed.add(line)
            }
        }
        return ParsedLog(telemetry, derate, shifter, wake, deepSleep, motorErrors, selfTests, boots, unparsed)
    }

    private fun parseTelemetry(epoch: Long, body: String): Telemetry {
        val f = body.split(";")
        fun d(i: Int): Double? = f.getOrNull(i)?.let { cleanNumber(it) }?.toDoubleOrNull()
        fun n(i: Int): Int? = f.getOrNull(i)?.let { cleanNumber(it) }?.toDoubleOrNull()?.toInt()
        val liPo = d(0)
        val volt = d(3)
        // The bike writes junk like "-247.-6" when the battery is disconnected: those fields do not
        // parse, or the voltage is implausible. Treat such rows as present-but-invalid.
        val valid = liPo != null && liPo in 0.0..100.0 && volt != null && volt in 0.0..100.0 && f.size >= 13
        return Telemetry(
            epoch = epoch,
            liPoSoc = liPo,
            bmsSoc = n(1),
            batTempC = parseBatTemp(f.getOrNull(2)),
            batVolt = volt,
            batCurrent = d(4),
            motorCurrent = d(5),
            motorTempC = n(6),
            driverTempC = n(7),
            speedKmh = d(8),
            odoKm = d(9),
            boost = n(10)?.let { it != 0 },
            lux = n(11),
            batDischarging = n(12),
            valid = valid,
        )
    }

    /** Strips the firmware's literal '%', stray spaces and trailing junk so a field like "21.2 %" parses. */
    private fun cleanNumber(s: String): String = s.trim().removeSuffix("%").trim()

    /**
     * BATTEMP (°C). The firmware prints it as "%d.%d" of temp/10 and (temp − whole×10) with C truncation
     * toward zero (states.c:2989-2992), so a sub-zero temperature comes out with the minus on the fraction,
     * not the whole: −2.5 °C → "-2.-5", −0.5 °C → "0.-5". A plain [cleanNumber] + toDouble drops both as
     * null. Reconstruct from the signed tenths; a normal decimal still takes the fast path.
     *
     * A disconnected-battery junk field like "-247.-6" reconstructs to a wild number, but the row is kept
     * out by the liPo/volt validity rule, so it never reaches the derate explainer.
     */
    internal fun parseBatTemp(field: String?): Double? {
        val s = field?.let { cleanNumber(it) } ?: return null
        if (s.isEmpty()) return null
        s.toDoubleOrNull()?.let { return it }
        val dot = s.indexOf('.')
        if (dot <= 0) return null
        val whole = s.substring(0, dot).toIntOrNull() ?: return null
        val frac = s.substring(dot + 1).toIntOrNull() ?: return null
        if (frac !in -9..9) return null
        return (whole * 10 + frac) / 10.0
    }
}
