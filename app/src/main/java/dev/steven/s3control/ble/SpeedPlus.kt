package dev.steven.s3control.ble

import android.content.Context
import dev.steven.s3control.data.BikeCredentials
import kotlin.math.roundToInt

/**
 * Speed++: the S3 derives its speed from the wheel-size flag WHEEL_SIZE (5564; 0 = 24", 1 = 28") and
 * hands the flag to the motor controller (mainware ble.c case 0x5564; sensor.c uses 0x1C200 or 0x21660
 * as the numerator). Telling a 28" bike it has 24" wheels makes it compute 115200/136800 of the real
 * speed: the motor cut-off moves up by the inverse (EU 25 shown ≈ 29.7 real, US 32 shown ≈ 38 real),
 * and the bike's own speedo and odometer read low by the same factor. YABA scales speed back.
 *
 * Everything hangs on the bike's FACTORY size ([WheelStock]), and the flag cannot tell it: it says what the bike is set
 * to, and Speed++ survives in the bike while YABA's own memory may not (a new install, cleared data, a second phone).
 * The account's model name does not tell it either: VanMoof reports a SKU there ("VM01-203-EU"), not "S3" or "X3".
 */
object SpeedPlus {
    const val WHEEL_24 = 0
    const val WHEEL_28 = 1
    const val FACTOR = 136_800f / 115_200f // 1.1875

    /**
     * Real speed for what the bike reports: a 28" bike set to 24" reads low (Speed++), a 24" bike (X3) set to 28" reads high
     * by the same ratio; a bike on its factory size, or one whose factory size is open, reads as it is.
     */
    fun factor(stock: Int?, current: Int?): Float = when {
        stock == WHEEL_28 && current == WHEEL_24 -> FACTOR
        stock == WHEEL_24 && current == WHEEL_28 -> 1f / FACTOR
        else -> 1f
    }

    fun realKmh(rawKmh: Int?, factor: Float): Int? = rawKmh?.let { (it * factor).roundToInt() }

    /** The real top speed for a cap the bike shows, e.g. 25 → 30 (29.7). */
    fun realCapKmh(capKmh: Int): Float = capKmh * FACTOR

    /** "S3" or "X3" as a word of its own: "VanMoof S3", "S3 2022", "X3". Not "ES3" (the mainware's name for both models). */
    private val MODEL_WORD = Regex("\\b([SX])3\\b", RegexOption.IGNORE_CASE)

    /**
     * The factory wheel of a model name where it is certain (S3 = 28", X3 = 24"), else null: a SKU such as "VM01-203-EU",
     * "ES3.1 Main", a name that names both models ("S3/X3"), nothing at all.
     */
    fun wheelOfModel(model: String?): Int? {
        if (model.isNullOrBlank()) return null
        val letters = MODEL_WORD.findAll(model).map { it.groupValues[1].uppercase() }.toSet()
        return when (letters) {
            setOf("S") -> WHEEL_28
            setOf("X") -> WHEEL_24
            else -> null
        }
    }

    /** Stock wheel from the bike's model name where it is certain (S3 = 28", X3 = 24"), else null. */
    fun stockFromModel(bike: BikeCredentials?): Int? = wheelOfModel(bike?.modelName)

    /**
     * The factory wheel, from the most certain source there is: the model name of the account or key file, the bike's own
     * model characteristic (5549, [deviceModel]), the owner's answer to the question, and only then the setting. A 28" read
     * (the first one YABA remembers, or the current one) settles it: an X3 leaves the factory on 24", and Speed++ only moves
     * a 28" bike to 24" and back. A 24" read does not: it is an X3 or an S3 with Speed++ on, so the stock stays unknown and
     * the owner is asked ([SpeedPlusView.ASK]).
     */
    fun stock(modelName: String?, deviceModel: String?, answer: Int?, firstRead: Int?, current: Int?): WheelStock {
        wheelOfModel(modelName)?.let { return WheelStock(it, WheelStock.Source.MODEL) }
        wheelOfModel(deviceModel)?.let { return WheelStock(it, WheelStock.Source.BIKE) }
        if (answer == WHEEL_24 || answer == WHEEL_28) return WheelStock(answer, WheelStock.Source.OWNER)
        if (firstRead == WHEEL_28 || current == WHEEL_28) return WheelStock(WHEEL_28, WheelStock.Source.READ)
        return WheelStock.UNKNOWN
    }

    /**
     * What the Speed++ block shows (pure, so it is unit-tested). An S3 owner always has a control: the switch (greyed while
     * not connected), the read, or the question. Only a bike settled as 24" from the factory gets the sentence alone, and the
     * way back to 24" while it reads 28".
     */
    fun view(ready: Boolean, wheel: Int?, stock: WheelStock): SpeedPlusView = when {
        stock.wheel == WHEEL_24 && ready && wheel == WHEEL_28 -> SpeedPlusView.BACK_TO_24
        stock.wheel == WHEEL_24 -> SpeedPlusView.NOT_28
        !ready -> SpeedPlusView.OFFLINE
        wheel == null -> SpeedPlusView.UNREAD
        stock.wheel == WHEEL_28 -> SpeedPlusView.SWITCH
        else -> SpeedPlusView.ASK
    }

    /**
     * What the stored first read becomes after a read of [read] ([stored] null: nothing stored yet). The first read stays,
     * except that a 28″ read replaces a stored 24″: a 28″ read settles the factory size ([stock]), and this keeps it settled
     * once the owner switches Speed++ on (otherwise a 24″ first read, taken with Speed++ already on, would bring the question
     * back and take the switch away just after it was used).
     */
    fun firstReadAfter(stored: Int?, read: Int): Int = if (stored == null || read == WHEEL_28) read else stored

    /** The "Ab Werk: 28″ (S3) | 24″ (X3)" bar: while YABA asks, and afterwards, so a wrong answer can be put right. */
    fun showsStockChoice(view: SpeedPlusView, stock: WheelStock): Boolean =
        view == SpeedPlusView.ASK || stock.source == WheelStock.Source.OWNER
}

/** What YABA knows of a bike's factory wheel size ([wheel] null = unknown) and where it comes from ([SpeedPlus.stock]). */
data class WheelStock(val wheel: Int?, val source: Source) {
    enum class Source {
        /** The model name of the account or the key file says S3 or X3. */
        MODEL,

        /** The bike's own model characteristic (5549) says S3 or X3. */
        BIKE,

        /** The owner answered "28″ (S3)" or "24″ (X3)". */
        OWNER,

        /** The bike read 28" (now, or the first time YABA read it). */
        READ,

        /** Nothing settles it: the bike reads 24" and no name says which model it is. */
        NONE,
    }

    companion object {
        val UNKNOWN = WheelStock(null, Source.NONE)
    }
}

/** What the Speed++ block on the Bike tab (and the question in the onboarding) shows ([SpeedPlus.view]). */
enum class SpeedPlusView {
    /** Not connected: the switch greyed out, with the size the bike had last. */
    OFFLINE,

    /** Connected, but the bike did not report its size: "Laufradgröße lesen". */
    UNREAD,

    /** The bike reads 24" and nothing says whether it is an S3 or an X3: the question. */
    ASK,

    /** A 24" bike from the factory (the model, or the owner's answer, says X3): the sentence. */
    NOT_28,

    /**
     * A 24" bike from the factory that reads 28" (a wrong answer followed by "28″ ab Werk", or another tool): it shows more
     * speed than it rides and cuts assist early. The sentence and the way back to 24".
     */
    BACK_TO_24,

    /** A 28" bike from the factory: the switch. */
    SWITCH,
}

/**
 * Per bike (by MAC): the wheel size YABA first read, before it ever wrote one, or a later 28″ read (the key is the bare MAC, as since 1.0); the
 * size it read last (for the greyed switch while not connected); and the owner's answer to the question.
 */
class SpeedPlusStore(context: Context) {
    private val prefs = context.getSharedPreferences("speedplus", Context.MODE_PRIVATE)

    fun stock(bike: BikeCredentials?, deviceModel: String?, current: Int?): WheelStock {
        val mac = bike?.normalizedMac
        return SpeedPlus.stock(bike?.modelName, deviceModel, get(mac?.let(::answerKey)), get(mac), current)
    }

    /** Every read: the first one is kept ([SpeedPlus.firstReadAfter]: a 28″ read replaces a 24″ one), the last one is written only when it changed. */
    fun rememberRead(bike: BikeCredentials?, value: Int) {
        val mac = bike?.normalizedMac ?: return
        val edit = prefs.edit()
        var changed = false
        val first = get(mac)
        val nextFirst = SpeedPlus.firstReadAfter(first, value)
        if (nextFirst != first) { edit.putInt(mac, nextFirst); changed = true }
        if (get(lastKey(mac)) != value) { edit.putInt(lastKey(mac), value); changed = true }
        if (changed) edit.apply()
    }

    fun lastRead(bike: BikeCredentials?): Int? = bike?.normalizedMac?.let { get(lastKey(it)) }

    /** The owner's "28″ (S3)" or "24″ (X3)"; null forgets it. */
    fun setAnswer(bike: BikeCredentials?, wheel: Int?) {
        val mac = bike?.normalizedMac ?: return
        if (wheel == null) prefs.edit().remove(answerKey(mac)).apply() else prefs.edit().putInt(answerKey(mac), wheel).apply()
    }

    /** Forgets the reads of [bike] (the debug demos start from a known store). */
    fun forgetReads(bike: BikeCredentials?) {
        val mac = bike?.normalizedMac ?: return
        prefs.edit().remove(mac).remove(lastKey(mac)).apply()
    }

    private fun get(key: String?): Int? = key?.let { prefs.getInt(it, -1).takeIf { v -> v >= 0 } }

    private fun lastKey(mac: String) = "last:$mac"

    private fun answerKey(mac: String) = "stock:$mac"
}
