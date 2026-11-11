package dev.steven.s3control.data

import dev.steven.s3control.I18n
import dev.steven.s3control.R
import dev.steven.s3control.ble.BikeFamily
import dev.steven.s3control.ble.S2Gatt
import org.json.JSONArray
import org.json.JSONObject

/** What we need to talk to one bike. Survives the VanMoof cloud going away. */
data class BikeCredentials(
    val id: Long?,
    val name: String,
    val ownerName: String?,
    val mac: String,
    val encryptionKey: String,
    val userKeyId: Int,
    val frameNumber: String? = null,
    val modelName: String? = null,
    val colorPrimary: String? = null,
    val colorSecondary: String? = null,
    val thumbnailUrl: String? = null,
    val bleProfile: String? = null,
    val fromAccount: Boolean = false,
    /** Renamed in this app: a later account refresh keeps the local name. */
    val customName: Boolean = false,
    /**
     * The model the person picked in the garage for a bike the account does not describe (S2 or S3 only,
     * [withModel]); null = the [bleProfile] decides. Stored only when set, so other vault entries stay as they were.
     */
    val chosenFamily: BikeFamily? = null,
) {
    /**
     * The bike generation: the garage choice, else from [bleProfile]. A hint only: after connecting, the GATT
     * services decide.
     */
    val family: BikeFamily get() = chosenFamily ?: BikeFamily.fromProfile(bleProfile)

    /**
     * The family the refusal of the older VanMoofs' shared service is read from ([BikeFamily.sharedServiceRefusal]).
     * S3/X3 is the manual form's default, so an S3 stored for a bike no profile describes may be an S2 whose owner left
     * it: such a bike counts as UNKNOWN here, as every hand-added bike did before the form had the choice, and gets the
     * one-time "set the model in the Garage" message instead of a retried "not an S3". Everywhere else it is an S3.
     */
    val refusalFamily: BikeFamily
        get() {
            val defaulted = chosenFamily == BikeFamily.S3 && BikeFamily.fromProfile(bleProfile) == BikeFamily.UNKNOWN
            return if (defaulted) BikeFamily.UNKNOWN else family
        }

    /**
     * This bike with the model picked in the garage (S2 or S3; anything else is ignored). A choice the profile
     * already makes is not stored, and S3 on a bike without a known profile is not stored either: that bike stays
     * exactly what it was before (UNKNOWN, which behaves as an S3).
     */
    fun withModel(model: BikeFamily): BikeCredentials {
        if (model != BikeFamily.S2 && model != BikeFamily.S3) return this
        val profile = BikeFamily.fromProfile(bleProfile)
        val chosen = when {
            model == profile -> null
            model == BikeFamily.S3 && profile == BikeFamily.UNKNOWN -> null
            else -> model
        }
        return copy(chosenFamily = chosen)
    }

    /**
     * BLE advertising names. S2/X2: VANMOOF- plus the last 6 MAC digits, in both cases ([S2Gatt.deviceNames]);
     * for a profile that only probably means S2 ([BikeFamily.isTentative]) the S3 names follow, so an S3 with that
     * profile is still found by name. Everything else (S3, unknown profile): ES3-/EX3- followed by the MAC without
     * colons, as always.
     */
    val advertisedNames: List<String>
        get() {
            if (family == BikeFamily.S2) {
                val s2 = S2Gatt.deviceNames(mac)
                return if (BikeFamily.isTentative(bleProfile)) s2 + s3Names() else s2
            }
            return s3Names()
        }

    private fun s3Names(): List<String> {
        val bare = mac.replace(":", "").uppercase()
        return listOf("ES3-$bare", "EX3-$bare")
    }

    val normalizedMac: String get() = normalizeMac(mac)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id ?: JSONObject.NULL)
        put("name", name)
        put("ownerName", ownerName ?: JSONObject.NULL)
        put("mac", mac)
        put("encryptionKey", encryptionKey)
        put("userKeyId", userKeyId)
        put("frameNumber", frameNumber ?: JSONObject.NULL)
        put("modelName", modelName ?: JSONObject.NULL)
        put("colorPrimary", colorPrimary ?: JSONObject.NULL)
        put("colorSecondary", colorSecondary ?: JSONObject.NULL)
        put("thumbnailUrl", thumbnailUrl ?: JSONObject.NULL)
        put("bleProfile", bleProfile ?: JSONObject.NULL)
        put("fromAccount", fromAccount)
        put("customName", customName)
        chosenFamily?.let { put(CHOSEN_FAMILY, it.name) }
    }

    companion object {
        /** Vault key of [chosenFamily]: "S2" or "S3", absent when the profile decides. */
        private const val CHOSEN_FAMILY = "chosenFamily"

        fun fromJson(o: JSONObject) = BikeCredentials(
            id = if (o.isNull("id")) null else o.optLong("id"),
            // The default name is resolved only when needed: unit tests parse JSON without resources.
            name = if (o.has("name") && !o.isNull("name")) o.optString("name") else I18n.get(R.string.core_default_bike_name),
            ownerName = o.optStringOrNull("ownerName"),
            mac = o.getString("mac"),
            encryptionKey = o.getString("encryptionKey"),
            userKeyId = o.getInt("userKeyId"),
            frameNumber = o.optStringOrNull("frameNumber"),
            modelName = o.optStringOrNull("modelName"),
            colorPrimary = o.optStringOrNull("colorPrimary"),
            colorSecondary = o.optStringOrNull("colorSecondary"),
            thumbnailUrl = o.optStringOrNull("thumbnailUrl"),
            bleProfile = o.optStringOrNull("bleProfile"),
            fromAccount = o.optBoolean("fromAccount", false),
            customName = o.optBoolean("customName", false),
            chosenFamily = o.optStringOrNull(CHOSEN_FAMILY)?.let { v ->
                BikeFamily.entries.firstOrNull { it.name == v }?.takeIf { it == BikeFamily.S2 || it == BikeFamily.S3 }
            },
        )

        fun listToJson(bikes: List<BikeCredentials>) = JSONArray().apply { bikes.forEach { put(it.toJson()) } }
        fun listFromJson(a: JSONArray?) = buildList {
            if (a != null) for (i in 0 until a.length()) runCatching { add(fromJson(a.getJSONObject(i))) }
        }
    }
}

data class ApiSession(
    val token: String,
    val refreshToken: String,
    val email: String,
    val obtainedAtMillis: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("token", token)
        put("refreshToken", refreshToken)
        put("email", email)
        put("obtainedAt", obtainedAtMillis)
    }

    companion object {
        fun fromJson(o: JSONObject?) = o?.let {
            runCatching {
                ApiSession(it.getString("token"), it.getString("refreshToken"), it.optString("email"), it.optLong("obtainedAt"))
            }.getOrNull()
        }
    }
}

fun normalizeMac(raw: String): String {
    val hex = raw.filter { it.isLetterOrDigit() }.uppercase()
    if (hex.length != 12) return raw.uppercase()
    return hex.chunked(2).joinToString(":")
}

fun JSONObject.optStringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
