package dev.steven.s3control.data

import dev.steven.s3control.I18n
import dev.steven.s3control.R
import dev.steven.s3control.ble.BikeCipher
import dev.steven.s3control.ble.BikeFamily
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The account side: login, bikes and their keys, sharing. Keys are cached in the [Vault]
 * so the app keeps working when the VanMoof cloud does not.
 */
class AccountRepository(private val vault: Vault, private val api: VanMoofApi) {
    private val _vaultError = MutableStateFlow<String?>(null)
    /** Set when the vault could not be read or written; nothing is overwritten while it is set. */
    val vaultError: StateFlow<String?> = _vaultError.asStateFlow()

    private val _contents = MutableStateFlow(
        runCatching { vault.load() }.getOrElse { _vaultError.value = it.message; Vault.Contents() },
    )
    val contents: StateFlow<Vault.Contents> = _contents.asStateFlow()

    val isLoggedIn: Boolean get() = _contents.value.session != null

    /** Logs in, downloads bikes with keys and stores both. Returns names of bikes without a usable key. */
    suspend fun login(email: String, password: String): AccountBikes {
        val session = api.authenticate(email.trim(), password)
        val result = api.bikes(session)
        saveOrThrow { c -> c.copy(session = session, bikes = merge(c.bikes, result.bikes)) }
        return result
    }

    suspend fun refreshBikes(): AccountBikes {
        val result = withSession { api.bikes(it) }
        saveOrThrow { c -> c.copy(bikes = merge(c.bikes, result.bikes)) }
        return result
    }

    /** Ends the cloud session but keeps every cached bike key. */
    fun logout() = save { it.copy(session = null) }

    fun addManual(bike: BikeCredentials) = save { c ->
        c.copy(bikes = c.bikes.filterNot { it.normalizedMac == bike.normalizedMac } + bike)
    }

    fun remove(mac: String) = save { c ->
        val m = normalizeMac(mac)
        c.copy(bikes = c.bikes.filterNot { it.normalizedMac == m }, lastBikeMac = c.lastBikeMac.takeUnless { it == m })
    }

    fun rename(mac: String, name: String) = save { c ->
        val m = normalizeMac(mac)
        c.copy(bikes = c.bikes.map { if (it.normalizedMac == m) it.copy(name = name, customName = true) else it })
    }

    /** The model picked in the garage for a bike the account does not describe ([BikeCredentials.withModel]). */
    fun setModel(mac: String, model: BikeFamily) = save { c ->
        val m = normalizeMac(mac)
        c.copy(bikes = c.bikes.map { if (it.normalizedMac == m) it.withModel(model) else it })
    }

    fun setLastBike(mac: String) = save { it.copy(lastBikeMac = normalizeMac(mac)) }

    suspend fun shares(bikeId: Long): List<Share> = withSession { api.shares(it, bikeId) }

    suspend fun share(bikeId: Long, email: String, durationSeconds: Long?): Share? =
        withSession { api.share(it, bikeId, email.trim(), durationSeconds) }

    suspend fun revoke(guid: String) = withSession { api.revoke(it, guid) }

    /** Backup of every bike key as JSON, for the user to keep somewhere safe. */
    fun exportKeys(): String = JSONObject().apply {
        put("format", "yaba-keys-v1")
        put("bikes", BikeCredentials.listToJson(_contents.value.bikes))
    }.toString(2)

    class ImportResult(val imported: Int, val skipped: Int)

    /** Accepts our own export, a mooovy `vm-bike-credentials` array, or a single bike object. */
    fun importKeys(text: String): ImportResult {
        val trimmed = text.trim()
        val array: JSONArray = try {
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> JSONObject(trimmed).let { o -> o.optJSONArray("bikes") ?: JSONArray().put(o) }
            }
        } catch (e: JSONException) {
            throw IllegalArgumentException(I18n.get(R.string.core_error_no_valid_keys), e)
        }
        var skipped = 0
        val parsed = (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null.also { skipped++ }
            runCatching {
                // mooovy stores {mac, encryptionKey, userKeyId, name, ...}
                val b = BikeCredentials.fromJson(o)
                val mac = normalizeMac(o.getString("mac"))
                val key = b.encryptionKey.trim().lowercase()
                require(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(mac)) { "MAC" }
                require(BikeCipher.isValidKey(key)) { "key" }
                require(b.userKeyId in 0..255) { "userKeyId" }
                b.copy(mac = mac, encryptionKey = key, fromAccount = false)
            }.getOrNull().also { if (it == null) skipped++ }
        }
        if (parsed.isEmpty()) throw IllegalArgumentException(I18n.get(R.string.core_error_no_valid_keys))
        saveOrThrow { c -> c.copy(bikes = merge(c.bikes, parsed)) }
        return ImportResult(parsed.size, skipped)
    }

    private suspend fun <T> withSession(block: suspend (ApiSession) -> T): T {
        val session = _contents.value.session ?: throw ApiException(I18n.get(R.string.core_error_not_logged_in), 401)
        return try {
            block(session)
        } catch (e: ApiException) {
            // Only an expired token is worth a refresh; "not authorised for this bike" is final.
            if (e.httpStatus != 401 || (e.type != null && e.type != "AuthenticationException")) throw e
            val fresh = try {
                api.refresh(session)
            } catch (r: ApiException) {
                if (r.httpStatus == 401) save { it.copy(session = null) }
                throw r
            }
            save { it.copy(session = fresh) }
            block(fresh)
        }
    }

    /** Saves; on a vault failure keeps the in-memory state, reports the error and never overwrites. */
    private fun save(transform: (Vault.Contents) -> Vault.Contents) {
        runCatching { saveOrThrow(transform) }
    }

    private fun saveOrThrow(transform: (Vault.Contents) -> Vault.Contents) {
        try {
            _contents.value = vault.update(transform)
            _vaultError.value = null
        } catch (e: Exception) {
            _vaultError.value = e.message ?: I18n.get(R.string.core_vault_unavailable)
            throw e
        }
    }

    internal companion object {
        /**
         * The bike list after a login, refresh or import. An account copy replaces the stored bike, but keeps a custom
         * name and the garage's model choice while the account profile does not settle the model ([BikeFamily.settles]):
         * otherwise every refresh would undo "S2/X2" for an account S2 with an unknown profile, or a key-imported S2.
         */
        fun merge(existing: List<BikeCredentials>, incoming: List<BikeCredentials>): List<BikeCredentials> {
            val byMac = LinkedHashMap<String, BikeCredentials>()
            existing.forEach { byMac[it.normalizedMac] = it }
            incoming.forEach { b ->
                val old = byMac[b.normalizedMac]
                byMac[b.normalizedMac] = when {
                    old == null -> b
                    b.fromAccount -> {
                        val named = if (old.customName) b.copy(name = old.name, customName = true) else b
                        val chosen = old.chosenFamily?.takeIf { !BikeFamily.settles(b.bleProfile) }
                        if (chosen != null) named.withModel(chosen) else named
                    }
                    // Manual/imported keys win, but keep what the account knew about the bike.
                    else -> b.copy(
                        name = old.name,
                        customName = old.customName,
                        id = b.id ?: old.id,
                        ownerName = b.ownerName ?: old.ownerName,
                        frameNumber = b.frameNumber ?: old.frameNumber,
                        modelName = b.modelName ?: old.modelName,
                        colorPrimary = b.colorPrimary ?: old.colorPrimary,
                        colorSecondary = b.colorSecondary ?: old.colorSecondary,
                        thumbnailUrl = b.thumbnailUrl ?: old.thumbnailUrl,
                        bleProfile = b.bleProfile ?: old.bleProfile,
                        chosenFamily = b.chosenFamily ?: old.chosenFamily,
                        fromAccount = old.fromAccount && old.encryptionKey.equals(b.encryptionKey, ignoreCase = true) &&
                            old.userKeyId == b.userKeyId,
                    )
                }
            }
            return byMac.values.toList()
        }
    }
}
