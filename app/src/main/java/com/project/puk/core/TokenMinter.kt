package com.project.puk.core

import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Mints fresh access tokens without opening any WebView: the web player's
 * own /api/token endpoint accepts a cookie-authenticated TOTP challenge, so
 * sp_dc alone is enough for a silent refresh.
 */
object TokenMinter {

    private const val TOKEN_URL = "https://open.spotify.com/api/token"
    private const val SERVER_TIME_URL = "https://open.spotify.com/api/server-time"
    private const val LEGACY_URL = "https://open.spotify.com/get_access_token?reason=transport&productType=web_player"
    private const val ORIGIN = "https://open.spotify.com"

    private val mutex = Mutex()

    suspend fun <T> changingSession(block: suspend () -> T): T = mutex.withLock { block() }

    /** Returns a usable access token, minting one when missing or expired. */
    suspend fun ensure(context: Context): String {
        val current = Auth.token(context)
        if (current.isNotBlank() && !Auth.tokenExpired(context)) return current
        return mutex.withLock {
            val latest = Auth.token(context)
            if (latest.isNotBlank() && !Auth.tokenExpired(context)) latest
            else mint(context) ?: latest
        }
    }

    /** Mints a brand new token; returns the existing one if another caller already refreshed it. */
    suspend fun refresh(context: Context): String? = mutex.withLock {
        val current = Auth.token(context)
        if (current.isNotBlank() && !Auth.tokenExpired(context)) current
        else mint(context)
    }

    private suspend fun mint(context: Context): String? {
        val cookie = cookieHeader(context)
        if (cookie.isBlank()) {
            Log.w("Token", "no sp_dc cookie, cannot mint")
            return null
        }
        // TOTP variant first (returns an explicit expiry), legacy endpoint second.
        mintWithTotp(context, cookie)?.let { return it }
        mintLegacy(context, cookie)?.let { return it }
        return null
    }

    private suspend fun mintWithTotp(context: Context, cookie: String): String? {
        val serverSeconds = fetchServerSeconds(cookie)
        val local = Totp.code(System.currentTimeMillis())
        val serverTotp = if (serverSeconds > 0L) Totp.code(serverSeconds * 1000L) else "unavailable"
        val url = "$TOKEN_URL?reason=init&productType=web-player" +
            "&totp=$local&totpServer=$serverTotp&totpVer=${Totp.version()}"
        var response = Net.get(url, headers(cookie), tag = "token")
        if (response?.code == 429) {
            // Rate limited: honour Retry-After (capped) once before falling back.
            val wait = response.retryAfterSeconds().coerceIn(1L, 10L)
            Log.w("Token", "api/token rate limited, waiting ${wait}s")
            delay(wait * 1000)
            response = Net.get(url, headers(cookie), tag = "token")
        }
        val json = response?.json() ?: run {
            Log.w("Token", "api/token ${response?.code ?: "no response"} not-json")
            return null
        }
        if (json.optBoolean("isAnonymous", false)) {
            Log.w("Token", "api/token came back anonymous")
            return null
        }
        val token = json.optString("accessToken")
        if (token.isBlank()) return null
        val expires = json.optLong("accessTokenExpirationTimestampMs", 0L)
        Auth.saveToken(context, token, expires)
        Log.i("Token", "minted via api/token, ${token.length}B, ttl=${if (expires > 0) (expires - System.currentTimeMillis()) / 1000 else "?"}s")
        return token
    }

    private suspend fun mintLegacy(context: Context, cookie: String): String? {
        var response = Net.get(LEGACY_URL, headers(cookie), tag = "token-legacy")
        if (response?.code == 429) {
            val wait = response.retryAfterSeconds().coerceIn(1L, 10L)
            Log.w("Token", "legacy token rate limited, waiting ${wait}s")
            delay(wait * 1000)
            response = Net.get(LEGACY_URL, headers(cookie), tag = "token-legacy")
        }
        val json = response?.json() ?: return null
        if (json.optBoolean("isAnonymous", false)) return null
        val token = json.optString("accessToken")
        if (token.isBlank()) return null
        Auth.saveToken(context, token, json.optLong("accessTokenExpirationTimestampMs", 0L))
        Log.i("Token", "minted via legacy endpoint, ${token.length}B")
        return token
    }

    private suspend fun fetchServerSeconds(cookie: String): Long {
        val response = Net.get(SERVER_TIME_URL, headers(cookie), tag = "server-time")
        val json = response?.takeIf { it.ok }?.json() ?: return 0L
        return json.optLong("serverTime", 0L) / 1000L
    }

    private fun headers(cookie: String): Map<String, String> = mapOf(
        "Cookie" to cookie,
        "User-Agent" to Net.DESKTOP_UA,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$ORIGIN/",
        "Origin" to ORIGIN,
        "app-platform" to "WebPlayer",
    )

    private fun cookieHeader(context: Context): String {
        val dc = Auth.spDc(context)
        if (dc.isNotBlank()) return "sp_dc=$dc"
        val jar = android.webkit.CookieManager.getInstance().getCookie(ORIGIN)
        return jar.orEmpty()
    }
}
