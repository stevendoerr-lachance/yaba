package dev.steven.s3control.data

import android.util.Base64
import dev.steven.s3control.I18n
import dev.steven.s3control.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ApiException(message: String, val httpStatus: Int? = null, val type: String? = null) : Exception(message)

data class Share(
    val guid: String,
    val email: String,
    val expiresAt: String?,
    val durationSeconds: Long?,
    val role: String?,
)

data class AccountBikes(
    val bikes: List<BikeCredentials>,
    /** Bikes on the account that have no personal key yet (factory key) or no MAC (S5/A5). */
    val skipped: List<String>,
)

/**
 * Legacy VanMoof v8 API. my.vanmoof.com went NXDOMAIN in 2026; api.vanmoof-api.com is the
 * same backend and still answers, so it is tried first and the old host is only a fallback.
 */
class VanMoofApi(
    private val hosts: List<String> = listOf(
        "https://api.vanmoof-api.com/v8",
        "https://my.vanmoof.com/api/v8",
    ),
) {
    private class Response(val status: Int, val body: String)

    suspend fun authenticate(email: String, password: String): ApiSession {
        val basic = Base64.encodeToString("$email:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        var r = request("POST", "/authenticate", authorization = "Basic $basic")
        if (r.status == 405) r = request("GET", "/authenticate", authorization = "Basic $basic")
        if (r.status == 401) throw ApiException(I18n.get(R.string.core_api_wrong_credentials), 401, "AuthenticationException")
        val json = unwrap(r)
        val token = json.optString("token")
        val refresh = json.optString("refreshToken")
        if (token.isBlank() || refresh.isBlank()) throw ApiException(I18n.get(R.string.core_api_no_token))
        return ApiSession(token, refresh, email, System.currentTimeMillis())
    }

    /** POST /token with the refresh token. The refresh token itself stays valid. */
    suspend fun refresh(session: ApiSession): ApiSession {
        val body = JSONObject().put("refreshToken", session.refreshToken)
        var r = request("POST", "/token", body = body)
        // Some clients send the refresh token as bearer as well; try that form once before giving up.
        if (r.status == 401) r = request("POST", "/token", bearer = session.refreshToken, body = body)
        if (r.status == 401) throw ApiException(I18n.get(R.string.core_api_session_expired), 401, "AuthenticationException")
        val json = unwrap(r)
        val token = json.optString("token").ifBlank { throw ApiException(I18n.get(R.string.core_api_refresh_no_token)) }
        return session.copy(
            token = token,
            refreshToken = json.optString("refreshToken").ifBlank { session.refreshToken },
            obtainedAtMillis = System.currentTimeMillis(),
        )
    }

    suspend fun bikes(session: ApiSession): AccountBikes {
        val json = unwrap(request("GET", "/getCustomerData?includeBikeDetails=", bearer = session.token))
        val details = json.optJSONObject("data")?.optJSONArray("bikeDetails") ?: JSONArray()
        val bikes = ArrayList<BikeCredentials>()
        val skipped = ArrayList<String>()
        for (i in 0 until details.length()) {
            val b = details.optJSONObject(i) ?: continue
            val name = b.optStringOrNull("name") ?: "VanMoof"
            val key = b.optJSONObject("key")
            val mac = b.optStringOrNull("macAddress")
            val encryptionKey = key?.optStringOrNull("encryptionKey")
            if (key == null || mac == null || encryptionKey == null) {
                skipped += name
                continue
            }
            val color = b.optJSONObject("modelColor")
            bikes += BikeCredentials(
                id = b.opt("id")?.toString()?.toLongOrNull(),
                name = name,
                ownerName = b.optStringOrNull("ownerName"),
                mac = normalizeMac(mac),
                encryptionKey = encryptionKey.lowercase(),
                userKeyId = key.optInt("userKeyId", 1),
                frameNumber = b.optStringOrNull("frameNumber"),
                modelName = b.optStringOrNull("modelName"),
                colorPrimary = color?.optStringOrNull("primary"),
                colorSecondary = color?.optStringOrNull("secondary"),
                thumbnailUrl = b.optJSONObject("links")?.optStringOrNull("thumbnail"),
                bleProfile = b.optStringOrNull("bleProfile"),
                fromAccount = true,
            )
        }
        return AccountBikes(bikes, skipped)
    }

    suspend fun shares(session: ApiSession, bikeId: Long): List<Share> {
        val json = unwrap(request("GET", "/getBikeSharingInvitationsForBike/$bikeId", bearer = session.token))
        val arr = json.optJSONArray("invitations") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.toShare() }
    }

    suspend fun share(session: ApiSession, bikeId: Long, email: String, durationSeconds: Long?): Share? {
        val body = JSONObject().put("email", email).put("bikeId", bikeId).put("role", "user")
        if (durationSeconds != null) body.put("duration", durationSeconds)
        val json = unwrap(request("POST", "/createBikeSharingInvitation", bearer = session.token, body = body))
        return json.optJSONObject("result")?.toShare()
    }

    suspend fun revoke(session: ApiSession, guid: String) {
        unwrap(request("POST", "/revokeBikeSharingInvitation/$guid", bearer = session.token, body = JSONObject()))
    }

    private fun JSONObject.toShare(): Share? {
        val guid = optStringOrNull("guid") ?: return null
        return Share(
            guid = guid,
            email = optString("email"),
            expiresAt = optStringOrNull("expiresAt"),
            durationSeconds = opt("duration")?.toString()?.toLongOrNull(),
            role = optStringOrNull("role"),
        )
    }

    private fun unwrap(r: Response): JSONObject {
        val json = runCatching { JSONObject(r.body) }.getOrNull()
        val type = json?.optStringOrNull("error")
        val message = json?.optStringOrNull("message")
        when {
            r.status == 429 -> throw ApiException(I18n.get(R.string.core_api_rate_limited), 429, type)
            r.status == 401 && (type == null || type == "AuthenticationException") ->
                throw ApiException(I18n.get(R.string.core_api_not_authenticated), 401, "AuthenticationException")
            r.status == 401 -> throw ApiException(
                if (type == "CustomerNotAuthorizedException") {
                    I18n.get(R.string.core_api_not_owner)
                } else {
                    message ?: I18n.get(R.string.core_api_no_permission)
                },
                401, type,
            )
            r.status >= 400 || type != null ->
                throw ApiException(message ?: type ?: I18n.get(R.string.core_api_server_error, r.status), r.status, type)
            json == null -> throw ApiException(I18n.get(R.string.core_api_unexpected), r.status)
        }
        return json!!
    }

    private suspend fun request(
        method: String,
        path: String,
        authorization: String? = null,
        bearer: String? = null,
        body: JSONObject? = null,
    ): Response = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        for (host in hosts) {
            try {
                return@withContext send(URL(host + path), method, authorization ?: bearer?.let { "Bearer $it" }, body)
            } catch (e: IOException) {
                // Only connection-phase failures move on to the next host; a timeout after sending
                // may mean the server did act, so a POST must not be repeated elsewhere.
                val connectPhase = e is UnknownHostException || e is ConnectException ||
                    e is NoRouteToHostException || e is SSLHandshakeException ||
                    (e is SocketTimeoutException && method == "GET")
                if (!connectPhase) {
                    throw ApiException(
                        I18n.get(if (e is SocketTimeoutException) R.string.core_api_timeout else R.string.core_api_connection_dropped),
                        null, e.javaClass.simpleName,
                    )
                }
                lastError = e
            }
        }
        throw ApiException(I18n.get(R.string.core_api_unreachable), null, lastError?.javaClass?.simpleName)
    }

    private fun send(url: URL, method: String, authorization: String?, body: JSONObject?): Response {
        val c = url.openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 12_000
            c.readTimeout = 20_000
            c.setRequestProperty("Api-Key", API_KEY)
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "YABA/1.0 (Android)")
            authorization?.let { c.setRequestProperty("Authorization", it) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = c.responseCode
            val stream = if (status >= 400) c.errorStream else c.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Response(status, text)
        } finally {
            c.disconnect()
        }
    }

    companion object {
        /** The public app key every S3/X3 client uses (also in mooovy, pymoof, VanMoofKit). */
        const val API_KEY = "fcb38d47-f14b-30cf-843b-26283f6a5819"
    }
}
