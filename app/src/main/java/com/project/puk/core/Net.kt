package com.project.puk.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Single OkHttp gateway for every Spotify endpoint the app talks to.
 * All requests go out with the web player's desktop identity so Spotify
 * treats SpotiPuk like a browser tab of open.spotify.com.
 */
object Net {

    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

    /** Web player build we impersonate everywhere (headers + registrations). */
    const val WEB_PLAYER_VERSION = "1.3.8.509.g26b6eac7300d"

    /** Public client id of the Spotify web player. */
    const val WEB_CLIENT_ID = "d8a5ed958d274c2e8ee717e6a4b0971d"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    class Response(
        val code: Int,
        val body: String,
        val bytes: ByteArray,
        val headers: Map<String, String>,
    ) {
        val ok: Boolean get() = code in 200..299
        fun json(): JSONObject? = runCatching { JSONObject(body) }.getOrNull()
        fun retryAfterSeconds(): Long {
            val raw = headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value ?: return 0L
            val value = raw.trim()
            value.toLongOrNull()?.let { return it.coerceAtLeast(0L) }
            return runCatching {
                val retryAt = java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                val remainingMs = retryAt.toInstant().toEpochMilli() - System.currentTimeMillis()
                if (remainingMs > 0) 1 + (remainingMs - 1) / 1_000 else 0L
            }.getOrDefault(0L)
        }
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap(), tag: String = ""): Response? =
        exec("GET", url, headers, null, null, tag)

    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap(), tag: String = ""): Response? =
        exec("POST", url, headers, json, "application/json", tag)

    suspend fun putJson(url: String, json: String, headers: Map<String, String> = emptyMap(), tag: String = ""): Response? =
        exec("PUT", url, headers, json, "application/json", tag)

    suspend fun delete(url: String, headers: Map<String, String> = emptyMap(), tag: String = ""): Response? =
        exec("DELETE", url, headers, null, null, tag)

    suspend fun postBlob(
        url: String,
        data: ByteArray,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
        tag: String = "",
    ): Response? = exec("POST", url, headers, null, contentType, tag, data)

    /**
     * Follows redirects (OkHttp does this automatically) and returns the final
     * URL — used to expand Spotify short links (spotify.link, *.app.link)
     * into real open.spotify.com content URLs.
     */
    suspend fun expand(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("User-Agent", DESKTOP_UA).build()
            client.newCall(request).execute().use { resp -> resp.request.url.toString() }
        } catch (e: Exception) {
            Log.e("Net", "expand $url failed: ${e.message}")
            null
        }
    }

    private suspend fun exec(
        method: String,
        url: String,
        headers: Map<String, String>,
        jsonBody: String?,
        contentType: String?,
        tag: String,
        rawBody: ByteArray? = null,
    ): Response? = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            when {
                rawBody != null -> builder.method(method, rawBody.toRequestBody(contentType?.toMediaType()))
                jsonBody != null -> builder.method(method, jsonBody.toRequestBody(contentType?.toMediaType()))
                else -> builder.method(method, null)
            }
            builder.tag(tag.ifBlank { url })
            val call = client.newCall(builder.build())
            call.execute().use { resp ->
                val raw = resp.body?.bytes() ?: ByteArray(0)
                val headerMap = resp.headers.toMultimap()
                    .mapValues { (_, v) -> v.firstOrNull().orEmpty() }
                Response(resp.code, String(raw, Charsets.UTF_8), raw, headerMap)
            }
        } catch (e: Exception) {
            Log.e(tag.ifBlank { "Net" }, "$method $url failed: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }
}
