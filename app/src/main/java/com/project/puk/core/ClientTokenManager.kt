package com.project.puk.core

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Mints the client-token the web player attaches to most API calls. The
 * token is anonymous (no user identity) and refreshed on a server-suggested
 * schedule; a 401/403 elsewhere triggers a forced re-mint.
 */
object ClientTokenManager {

    private const val ENDPOINT = "https://clienttoken.spotify.com/v1/clienttoken"
    private const val SKEW_MS = 60_000L

    private val mutex = Mutex()

    suspend fun get(context: Context, force: Boolean = false): String {
        val stored = Auth.clientToken(context)
        if (!force && stored.isNotBlank() && !Auth.clientTokenExpired(context)) return stored
        return mutex.withLock {
            val recheck = Auth.clientToken(context)
            if (!force && recheck.isNotBlank() && !Auth.clientTokenExpired(context)) return recheck
            mint(context).ifBlank { recheck }
        }
    }

    private suspend fun mint(context: Context): String {
        val body = JSONObject()
            .put(
                "client_data",
                JSONObject()
                    .put("client_version", Net.WEB_PLAYER_VERSION)
                    .put("client_id", Net.WEB_CLIENT_ID)
                    .put(
                        "js_sdk_data",
                        JSONObject()
                            .put("device_brand", "unknown")
                            .put("device_model", "unknown")
                            .put("os", "windows")
                            .put("os_version", "NT 10.0")
                            .put("device_id", Identity.deviceId(context))
                            .put("device_type", "computer"),
                    ),
            )
            .toString()
        val response = Net.postBlob(
            ENDPOINT,
            body.toByteArray(Charsets.UTF_8),
            "application/json",
            mapOf(
                "Accept" to "application/json",
                "User-Agent" to Net.DESKTOP_UA,
            ),
            tag = "clienttoken",
        )
        val json = response?.json() ?: run {
            Log.w("ClientToken", "mint failed ${response?.code ?: "no response"}")
            return ""
        }
        val granted = json.optJSONObject("granted_token") ?: json
        val token = granted.optString("token")
        if (token.isBlank()) {
            Log.w("ClientToken", "mint answered without a token")
            return ""
        }
        val refreshAfterSeconds = granted.optLong("refresh_after_seconds")
            .takeIf { it > 0L } ?: granted.optLong("expires_after_seconds")
        val expiry = if (refreshAfterSeconds > 0L) {
            System.currentTimeMillis() + refreshAfterSeconds * 1000L
        } else {
            0L
        }
        Auth.saveClientToken(context, token, expiry)
        Log.i("ClientToken", "minted ${token.length}B, refresh in ${refreshAfterSeconds}s")
        return token
    }

    /** Early refresh margin applied when deciding staleness. */
    fun isStale(context: Context): Boolean {
        val exp = SecurePrefs.prefs(context).getLong("client_token_exp", 0L)
        return exp - SKEW_MS <= System.currentTimeMillis()
    }
}
