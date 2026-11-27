package com.project.puk.core

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.webkit.CookieManager
import org.json.JSONObject

/**
 * Holds the logged-in identity: the captured cookie session (sp_dc is the
 * long lived one), the access/client tokens harvested or minted, and the
 * account profile.
 */
object Auth {

    data class Account(
        val id: String,
        val name: String,
        val email: String,
        val imageUrl: String,
        val product: String,
        val country: String,
    )

    private const val KEY_SAVED_ACCOUNTS = "saved_account_sessions"
    private const val KEY_RETURN_ACCOUNT = "adding_account_return_id"
    @Volatile var generation: Long = 0L
        private set

    private const val KEY_SESSION = "session_cookies"
    private const val KEY_ACCOUNT = "account_json"
    private const val KEY_TOKEN = "access_token"
    private const val KEY_TOKEN_EXP = "access_token_exp"
    private const val KEY_CLIENT_TOKEN = "client_token"
    private const val KEY_CLIENT_TOKEN_EXP = "client_token_exp"

    val SESSION_DOMAINS = listOf(
        "https://open.spotify.com",
        "https://accounts.spotify.com",
        "https://api-partner.spotify.com",
        "https://spclient.wg.spotify.com",
        "https://api.spotify.com",
        "https://www.spotify.com",
    )

    // ---- access token ----

    @Volatile private var tokenMemo: String? = null
    @Volatile private var tokenExpMemo: Long? = null
    @Volatile private var clientTokenMemo: String? = null

    fun saveToken(context: Context, token: String, expiresAtMs: Long = 0L) {
        if (token.isBlank()) return
        val exp = if (expiresAtMs > 0L) expiresAtMs else jwtExpiryMs(token)
        SecurePrefs.edit(context) {
            it.putString(KEY_TOKEN, token)
            it.putLong(KEY_TOKEN_EXP, exp)
        }
        tokenMemo = token
        tokenExpMemo = exp
        Log.i("Auth", "access token stored, ttl=${if (exp > 0) (exp - System.currentTimeMillis()) / 1000 else "?"}s")
    }

    fun token(context: Context): String {
        tokenMemo?.let { return it }
        val value = SecurePrefs.prefs(context).getString(KEY_TOKEN, "").orEmpty()
        tokenMemo = value
        return value
    }

    fun tokenExpiresAt(context: Context): Long {
        tokenExpMemo?.let { return it }
        val value = SecurePrefs.prefs(context).getLong(KEY_TOKEN_EXP, 0L)
        tokenExpMemo = value
        return value
    }

    fun tokenExpired(context: Context): Boolean {
        val exp = tokenExpiresAt(context)
        return exp > 0L && System.currentTimeMillis() >= exp
    }

    fun invalidateToken(context: Context) {
        SecurePrefs.edit(context) {
            it.remove(KEY_TOKEN).remove(KEY_TOKEN_EXP)
        }
        tokenMemo = ""
        tokenExpMemo = 0L
    }

    // ---- client token ----

    fun saveClientToken(context: Context, token: String, expiresAtMs: Long) {
        SecurePrefs.edit(context) {
            it.putString(KEY_CLIENT_TOKEN, token)
            it.putLong(KEY_CLIENT_TOKEN_EXP, expiresAtMs)
        }
        clientTokenMemo = token
    }

    fun clientToken(context: Context): String {
        clientTokenMemo?.let { return it }
        val value = SecurePrefs.prefs(context).getString(KEY_CLIENT_TOKEN, "").orEmpty()
        clientTokenMemo = value
        return value
    }

    fun clientTokenExpired(context: Context): Boolean {
        val exp = SecurePrefs.prefs(context).getLong(KEY_CLIENT_TOKEN_EXP, 0L)
        // exp == 0 means "unknown validity" (mint answered without a refresh
        // schedule, or the login bridge did not report one): keep using the
        // stored token instead of re-minting on every call — a 401/403
        // elsewhere triggers the forced re-mint.
        return exp > 0L && System.currentTimeMillis() >= exp
    }

    fun clearClientToken(context: Context) {
        SecurePrefs.edit(context) { it.remove(KEY_CLIENT_TOKEN).remove(KEY_CLIENT_TOKEN_EXP) }
        clientTokenMemo = ""
    }

    // ---- cookie session ----

    /** Snapshot of every Spotify cookie into one JSON string. Returns null when sp_dc is missing. */
    fun captureSession(context: Context): String? {
        val manager = CookieManager.getInstance()
        val map = JSONObject()
        var hasDc = false
        SESSION_DOMAINS.forEach { domain ->
            val cookie = manager.getCookie(domain)
            if (!cookie.isNullOrBlank()) {
                map.put(domain, cookie)
                if (cookie.contains("sp_dc=")) hasDc = true
            }
        }
        return if (hasDc) map.toString() else null
    }

    fun saveSession(context: Context, snapshot: String) {
        SecurePrefs.edit(context) { it.putString(KEY_SESSION, snapshot) }
    }

    /** Restores the saved cookies into the WebView jar (after logout / reinstall). */
    fun applySession(context: Context) {
        val snapshot = SecurePrefs.prefs(context).getString(KEY_SESSION, null) ?: return
        val map = runCatching { JSONObject(snapshot) }.getOrNull() ?: return
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        map.keys().asSequence().forEach { domain ->
            map.optString(domain).split(";")
                .map { it.trim() }
                .filter { it.contains("=") }
                .forEach { manager.setCookie(domain, it) }
        }
        manager.flush()
    }

    fun spDc(context: Context): String {
        val snapshot = SecurePrefs.prefs(context).getString(KEY_SESSION, null) ?: return ""
        val map = runCatching { JSONObject(snapshot) }.getOrNull() ?: return ""
        for (domain in SESSION_DOMAINS) {
            val raw = map.optString(domain, "")
            if (raw.isBlank()) continue
            val dc = raw.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("sp_dc=") }
            if (dc != null) return dc.removePrefix("sp_dc=")
        }
        return ""
    }

    // ---- account ----

    fun saveAccount(context: Context, account: Account) {
        val json = JSONObject()
            .put("id", account.id)
            .put("name", account.name)
            .put("email", account.email)
            .put("image", account.imageUrl)
            .put("product", account.product)
            .put("country", account.country)
        SecurePrefs.edit(context) { it.putString(KEY_ACCOUNT, json.toString()) }
        rememberCurrentAccount(context)
    }

    fun account(context: Context): Account? {
        val raw = SecurePrefs.prefs(context).getString(KEY_ACCOUNT, null) ?: return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        return Account(
            id = json.optString("id"),
            name = json.optString("name"),
            email = json.optString("email"),
            imageUrl = json.optString("image"),
            product = json.optString("product"),
            country = json.optString("country"),
        )
    }

    fun savedAccounts(context: Context): List<Account> = savedSessions(context).map { it.profile }

    private fun savedSessions(context: Context) =
        SavedAccounts.decode(SecurePrefs.prefs(context).getString(KEY_SAVED_ACCOUNTS, null))

    /** A failed commit is logged, not thrown: losing one prefs upsert must not crash the app. */
    private fun commitLogged(context: Context, what: String, edit: SharedPreferences.Editor.() -> Unit) {
        if (!SecurePrefs.prefs(context).edit().apply(edit).commit()) Log.e("Auth", "prefs commit failed: $what")
    }

    fun rememberCurrentAccount(context: Context) {
        val profile = account(context)?.takeIf { it.id.isNotBlank() } ?: return
        val cookies = SecurePrefs.prefs(context).getString(KEY_SESSION, null)?.takeIf { it.isNotBlank() } ?: return
        val saved = SavedAccounts.upsert(savedSessions(context), SavedAccountSession(profile, cookies))
        commitLogged(context, "saved_accounts upsert") {
            putString(KEY_SAVED_ACCOUNTS, SavedAccounts.encode(saved))
        }
    }

    fun returnAccountId(context: Context): String? = SecurePrefs.prefs(context).getString(KEY_RETURN_ACCOUNT, null)

    fun beginAddingAccount(context: Context) {
        rememberCurrentAccount(context)
        val previous = account(context)?.id ?: returnAccountId(context)
        clearActiveCredentials(context)
        commitLogged(context, "return_account") { putString(KEY_RETURN_ACCOUNT, previous) }
    }

    fun activateAccount(context: Context, id: String) {
        val selected = savedSessions(context).firstOrNull { it.profile.id == id }
            ?: error("Saved account is unavailable")
        // Save the account being left before replacing the active identity.
        rememberCurrentAccount(context)
        clearActiveCredentials(context)
        commitLogged(context, "session activate") { putString(KEY_SESSION, selected.cookies) }
        saveAccount(context, selected.profile)
        finishAddingAccount(context)
    }

    fun finishAddingAccount(context: Context) {
        SecurePrefs.edit(context) { it.remove(KEY_RETURN_ACCOUNT) }
    }

    /** Call on main, after account requests have stopped; await WebView's asynchronous clear. */
    suspend fun resetBrowserSession(context: Context) {
        val manager = CookieManager.getInstance()
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
            manager.removeAllCookies { if (continuation.isActive) continuation.resumeWith(Result.success(Unit)) }
        }
        val snapshot = SecurePrefs.prefs(context).getString(KEY_SESSION, null)
        val map = snapshot?.let { runCatching { JSONObject(it) }.getOrNull() }
        manager.setAcceptCookie(true)
        map?.keys()?.asSequence()?.forEach { domain ->
            map.optString(domain).split(";").map { it.trim() }.filter { it.contains("=") }.forEach { cookie ->
                kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                    manager.setCookie(domain, cookie) { if (continuation.isActive) continuation.resumeWith(Result.success(Unit)) }
                }
            }
        }
        manager.flush()
        android.webkit.WebStorage.getInstance().deleteAllData()
    }

    private fun clearActiveCredentials(context: Context) = synchronized(this) {
        generation++
        commitLogged(context, "clear active credentials") {
            remove(KEY_SESSION).remove(KEY_ACCOUNT).remove(KEY_TOKEN).remove(KEY_TOKEN_EXP)
            remove(KEY_CLIENT_TOKEN).remove(KEY_CLIENT_TOKEN_EXP)
        }
        tokenMemo = ""
        tokenExpMemo = 0L
        clientTokenMemo = ""
    }

    fun isLoggedIn(context: Context): Boolean = spDc(context).isNotBlank()

    fun logout(context: Context) {
        val id = account(context)?.id
        val remaining = savedSessions(context).filterNot { it.profile.id == id }
        commitLogged(context, "saved_accounts logout") {
            putString(KEY_SAVED_ACCOUNTS, SavedAccounts.encode(remaining))
            remove(KEY_RETURN_ACCOUNT)
        }
        clearActiveCredentials(context)
        Log.i("Auth", "logged out")
    }

    /** Expiry claim from a JWT bearer token; 0 when the token is opaque. */
    private fun jwtExpiryMs(token: String): Long {
        val parts = token.split('.')
        if (parts.size != 3) return 0L
        return runCatching {
            val payload = String(
                Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
                Charsets.UTF_8,
            )
            val exp = JSONObject(payload).optLong("exp", 0L)
            if (exp > 0L) exp * 1000L else 0L
        }.getOrDefault(0L)
    }
}
