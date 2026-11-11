package dev.steven.s3control.audio

import android.content.Context
import android.net.Uri
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import dev.steven.s3control.R

/**
 * Bells that ship with the app. Original sounds, synthesised from scratch by
 * `tools/bells/make_bells.py` and dedicated to the public domain (CC0, see `tools/bells/README.md`).
 * Voiced for the bike's small speaker and short enough that [BellConverter] keeps them at 44.1 kHz.
 *
 * No brand stings, sound marks or ringtone imitations on purpose: for those, pick your own file.
 * [keepLevel] sends a bell as recorded, without normalising (for a file that is clipped on purpose).
 * Order: the everyday bells first, the jokes after.
 */
enum class BundledBell(@RawRes val raw: Int, @StringRes val nameRes: Int, val emoji: String, val keepLevel: Boolean = false) {
    RINGRING(R.raw.bell_ringring, R.string.bells_name_ringring, "🚲"),
    TRAM(R.raw.bell_tram, R.string.bells_name_tram, "🚋"),
    HONK(R.raw.bell_honk, R.string.bells_name_honk, "🤡"),
    STADIUM(R.raw.bell_stadium, R.string.bells_name_stadium, "📣"),
    FANFARE(R.raw.bell_fanfare, R.string.bells_name_fanfare, "🎺"),
    CUCARACHA(R.raw.bell_cucaracha, R.string.bells_name_cucaracha, "🪳"),
    TROMBONE(R.raw.bell_trombone, R.string.bells_name_trombone, "😢"),
    NIGHTSHIFT(R.raw.bell_nightshift, R.string.bells_name_nightshift, "🌙"),
    JINGLE(R.raw.bell_jingle, R.string.bells_name_jingle, "👾"),
    UFO(R.raw.bell_ufo, R.string.bells_name_ufo, "🛸"),
    ;

    /**
     * `android.resource://<package>/<id>`: ContentResolver opens it just like the picker's content Uri,
     * so the bell takes the picker's path to the bike (BellConverter, then the custom bell upload).
     */
    fun uri(context: Context): Uri = Uri.parse("android.resource://" + context.packageName + "/" + raw)
}
