package dev.steven.s3control.ble

/**
 * "Warum zieht er gerade nicht?" — the motor's current limit is chosen by the firmware from the
 * battery's state of charge and temperature, not sent over BLE. This mirrors the firmware's own
 * lookup so YABA can explain a weak moment ("15 % Akku, deshalb nur 15 statt 30 A").
 *
 * The current limits the firmware applies by battery level and temperature, as documented in Knight1's S3 firmware notes (mainware 1.07.06, battery charge table).
 * The firmware passes charge-flags too; live we only know SoC and temperature, so [predict] uses
 * flags = 0 (the discharge / normal case). The real logged limits come from
 * [dev.steven.s3control.ride.BikeLogParser.DerateEvent]; this is the live approximation.
 *
 * Temperatures are whole-ish °C; the firmware compares a signed delta in 0.1 °C against the ceilings
 * 0, 5.0 and 60.0 °C. Current limits are the row's rate / 10 (rate 300 = 30.0 A, 0 = no assist).
 * Confidence: M–H (table verified line by line; live temperature only refreshes every 30 s in the log).
 */
object DerateTable {

    private data class Row(val socMax: Int, val tempMaxDeci: Int, val flagMask: Int, val rate: Int)

    private val TABLE = listOf(
        Row(3, 32767, 0x0000, 0), Row(5, 32767, 0x0000, 0),
        Row(13, 0, 0x4000, 100), Row(18, 0, 0x4000, 100),
        Row(22, 0, 0x4000, 100), Row(100, 0, 0x4000, 100),
        Row(13, 50, 0x0020, 100), Row(18, 50, 0x0020, 100),
        Row(22, 50, 0x0020, 150), Row(100, 50, 0x0020, 200),
        Row(13, 600, 0x0000, 100), Row(18, 600, 0x0000, 150),
        Row(22, 600, 0x0000, 200), Row(100, 600, 0x0000, 300),
        Row(13, 32767, 0x8000, 100), Row(18, 32767, 0x8000, 100),
        Row(22, 32767, 0x8000, 150), Row(100, 32767, 0x8000, 200),
    )

    /** What the explainer tells the rider. [limitAmps] null = outside the table (very hot, no flag). */
    data class Prediction(val limitAmps: Int?, val socPercent: Int, val tempC: Double) {
        val noAssist: Boolean get() = limitAmps == 0
        val full: Boolean get() = limitAmps != null && limitAmps >= 30
    }

    /**
     * The current limit the firmware would pick for this SoC and battery temperature, flags = 0.
     * Returns amps (whole), 0 for the empty/saving rows, or null if no row matches.
     */
    fun limitAmps(socPercent: Int, tempC: Double, flags: Int = 0): Int? {
        val tempDeci = Math.round(tempC * 10).toInt()
        for (r in TABLE) {
            if (socPercent <= r.socMax && (tempDeci <= r.tempMaxDeci || (r.flagMask and flags) != 0)) {
                return r.rate / 10
            }
        }
        return null
    }

    fun predict(socPercent: Int, tempC: Double): Prediction =
        Prediction(limitAmps(socPercent, tempC), socPercent, tempC)
}
