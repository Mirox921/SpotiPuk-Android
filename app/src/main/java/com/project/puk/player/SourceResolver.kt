package com.project.puk.player

import android.content.Context
import com.project.puk.core.Auth
import com.project.puk.core.ClientTokenManager
import com.project.puk.core.Log
import com.project.puk.core.Net
import com.project.puk.core.SecurePrefs
import com.project.puk.core.TokenMinter
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import android.os.SystemClock
import java.io.IOException

/**
 * Turns a spotify:track: URI into a playable CDN source:
 *   1. track-playback/v1/media        -> MP4 file ids (bitrate variants)
 *   2. seektables.spotifycdn.com      -> segment map + Widevine pssh
 *   3. storage-resolve/v2             -> actual CDN URL
 * DRM-protected sources additionally need a Widevine license endpoint
 * resolved from melody/v1/license_url.
 */
object SourceResolver {

    private const val FALLBACK_SPCLIENT = "guc3-spclient.spotify.com"
    private const val SEEKTABLE_HOST = "https://seektables.spotifycdn.com/v1/seektable/"
    private const val FREE_BITRATE = 128_000
    private val MP4_FORMATS = listOf(10, 11, 12, 13)

    data class AudioFile(val fileId: String, val format: Int, val bitrate: Int, val quality: String, val gainDb: Double)

    data class SegmentInfo(
        val offset: Int,
        val sizes: List<Int>,
        val durations: List<Long>,
        val timescale: Int,
        val pssh: String,
    ) {
        val totalDurationMs: Long get() = durations.sum() * 1000L / timescale.coerceAtLeast(1)
    }

    data class AudioSource(
        val uri: String,
        val file: AudioFile,
        val url: String,
        val segments: SegmentInfo,
        val spclient: String,
    )

    @Volatile
    private var spclientHost: String = ""

    suspend fun spclient(context: Context, refresh: Boolean = false): String {
        if (!refresh && spclientHost.isNotBlank()) return spclientHost
        val stored = SecurePrefs.prefs(context).getString("ap_spclient", null)
        val resolved = resolveSpclient()
        spclientHost = resolved ?: stored ?: FALLBACK_SPCLIENT
        if (resolved != null) {
            SecurePrefs.edit(context) { it.putString("ap_spclient", resolved) }
        }
        Log.i("Resolver", "spclient = $spclientHost")
        return spclientHost
    }

    private suspend fun resolveSpclient(): String? {
        val response = Net.get("https://apresolve.spotify.com/?type=spclient", tag = "apresolve") ?: return null
        val host = response.json()?.optJSONArray("spclient")?.optString(0).orEmpty()
        return host.ifBlank { null }
    }

    // ---- headers ----

    private suspend fun playHeaders(context: Context, forceClientToken: Boolean = false): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${TokenMinter.ensure(context)}",
        "client-token" to ClientTokenManager.get(context, forceClientToken),
        "app-platform" to "WebPlayer",
        "spotify-app-version" to Net.WEB_PLAYER_VERSION,
        "Accept-Language" to java.util.Locale.getDefault().toLanguageTag(),
        "User-Agent" to Net.DESKTOP_UA,
        "Accept" to "*/*",
    )

    private suspend fun licenseHeaders(context: Context, token: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer $token",
        "client-token" to ClientTokenManager.get(context),
        "Origin" to "https://open.spotify.com",
        "Referer" to "https://open.spotify.com/",
        "User-Agent" to Net.DESKTOP_UA,
        "Accept" to "*/*",
    )

    // ---- full pipeline ----

    data class TrackManifest(val uri: String, val spclient: String, val files: List<AudioFile>)

    suspend fun loadManifest(context: Context, uri: String): TrackManifest? {
        val host = spclient(context)
        val files = manifest(context, host, uri)
        return if (files.isEmpty()) null else TrackManifest(uri, host, files)
    }

    private suspend fun resolve(context: Context, manifest: TrackManifest): AudioSource? {
        val (uri, spclient, candidates) = manifest
        for (file in candidates) {
            val segments = seektable(file.fileId)
            val url = cdnUrl(context, spclient, file.fileId, file.format)
            if (segments == null || url == null) {
                Log.w("Resolver", "file ${file.fileId.take(8)} fmt=${file.format} unusable, trying next")
                continue
            }
            Log.i(
                "Resolver",
                "resolved $uri fmt=${file.format} ${file.bitrate}kbps segs=${segments.sizes.size} " +
                    "${segments.totalDurationMs}ms pssh=${segments.pssh.length}B",
            )
            return AudioSource(uri, file, url, segments, spclient)
        }
        return null
    }

    /** Retries once with a fresh spclient host on total failure. */
    suspend fun resolveWithRetry(
        context: Context,
        uri: String,
        prefetched: TrackManifest? = null,
    ): AudioSource? {
        val first = prefetched ?: loadManifest(context, uri)
        first?.let { resolve(context, it) }?.let { return it }
        Log.w("Resolver", "resolve failed once, refreshing spclient")
        spclient(context, refresh = true)
        return loadManifest(context, uri)?.let { resolve(context, it) }
    }

    // ---- steps ----

    private suspend fun manifest(context: Context, spclient: String, uri: String): List<AudioFile> {
        val url = "https://$spclient/track-playback/v1/media/$uri?manifestFileFormat=file_ids_mp4&manifest"
        var response = Net.get(url, playHeaders(context), "media")
        if (response?.code == 401 || response?.code == 403) {
            Auth.invalidateToken(context)
            TokenMinter.refresh(context)
            response = Net.get(url, playHeaders(context), "media")
        }
        val json = response?.takeIf { it.ok }?.json() ?: run {
            Log.w("Resolver", "manifest HTTP ${response?.code ?: "no response"}, ${response?.bytes?.size ?: 0} bytes")
            return emptyList()
        }
        val media = json.optJSONObject("media") ?: return emptyList()
        val entry = media.optJSONObject(uri)
            ?: media.keys().asSequence().mapNotNull { media.optJSONObject(it) }.firstOrNull()
            ?: return emptyList()
        val files = entry.optJSONObject("item")?.optJSONObject("manifest")?.optJSONArray("file_ids_mp4")
            ?: return emptyList()
        val parsed = (0 until files.length()).mapNotNull { i ->
            val node = files.optJSONObject(i) ?: return@mapNotNull null
            val id = node.optString("file_id")
            if (id.isBlank()) return@mapNotNull null
            AudioFile(
                fileId = id,
                format = node.optString("format").toIntOrNull() ?: 10,
                bitrate = node.optInt("bitrate", 0),
                quality = node.optString("audio_quality"),
                gainDb = node.optDouble("gain_db", 0.0),
            )
        }
        val premium = Auth.account(context)?.product?.uppercase() == "PREMIUM"
        return if (premium) {
            parsed.filter { it.format in MP4_FORMATS }.sortedByDescending { it.bitrate }
        } else {
            parsed.filter { it.format in MP4_FORMATS }.sortedBy { kotlin.math.abs(it.bitrate - FREE_BITRATE) }
        }
    }

    private suspend fun seektable(fileId: String): SegmentInfo? {
        val response = Net.get("$SEEKTABLE_HOST$fileId", tag = "seektable") ?: return null
        val json = response.json() ?: return null
        val segments = json.optJSONArray("segments") ?: return null
        val sizes = mutableListOf<Int>()
        val durations = mutableListOf<Long>()
        for (i in 0 until segments.length()) {
            val seg = segments.optJSONObject(i) ?: continue
            val size = seg.optInt("size", 0)
            if (size <= 0) continue
            sizes.add(size)
            durations.add(seg.optLong("duration", 0L))
        }
        if (sizes.isEmpty()) return null
        return SegmentInfo(
            offset = json.optInt("offset", 0),
            sizes = sizes,
            durations = durations,
            timescale = json.optLong("timescale", 44_100L).toInt(),
            pssh = json.optJSONObject("pssh")?.optString("widevine").orEmpty(),
        )
    }

    private suspend fun cdnUrl(context: Context, spclient: String, fileId: String, format: Int): String? {
        val url = "https://$spclient/storage-resolve/v2/files/audio/interactive/$format/$fileId" +
            "?version=10000000&product=9&platform=39&alt=json"
        val response = Net.get(url, playHeaders(context), "resolve") ?: return null
        val first = response.json()?.optJSONArray("cdnurl")?.optString(0).orEmpty()
        if (first.isBlank()) {
            Log.w("Resolver", "resolve ${response.code} ${response.body.take(120)}")
        }
        return first.ifBlank { null }
    }

    // ---- DRM ----

    private data class LicenseEndpoint(val host: String, val url: String, val expiresAt: Long)
    private var cachedLicenseEndpoint: LicenseEndpoint? = null
    private val licenseRequests = Mutex()
    @Volatile private var licenseRetryAt = 0L
    private var licenseRateLimitCount = 0

    fun licenseRetryDelayMs(): Long = (licenseRetryAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)

    class LicenseRateLimited : IOException("Spotify Widevine license rate limit")

    private suspend fun licenseEndpoint(context: Context, spclient: String): String {
        val now = System.currentTimeMillis() / 1000L
        cachedLicenseEndpoint?.let {
            if (it.host == spclient && now < it.expiresAt) return it.url
        }
        val url = "https://$spclient/melody/v1/license_url" +
            "?keysystem=com.widevine.alpha&mediatype=audio&sdk_name=harmony&sdk_version=4.81.1"
        val response = Net.get(url, playHeaders(context), "license-url")
        val json = response?.takeIf { it.ok }?.json()
        val uri = json?.optString("uri").orEmpty()
        if (response?.code == 429) {
            recordLicenseLimit(response, "endpoint discovery")
            throw LicenseRateLimited()
        }
        if (uri.isBlank()) Log.w("Resolver", "Widevine endpoint discovery HTTP ${response?.code}; using fallback")
        val resolved = when {
            uri.startsWith("http") -> uri
            uri.isNotBlank() -> "https://$spclient/$uri"
            else -> "https://$spclient/widevine-license/v1/audio/license" +
                "?keysystem=com.widevine.alpha&mediatype=audio&sdk_name=harmony&sdk_version=4.81.1"
        }
        val expires = json?.optLong("expires", 0L) ?: 0L
        val expiresAt = if (expires > now) expires else now + 60L
        cachedLicenseEndpoint = LicenseEndpoint(spclient, resolved, expiresAt)
        Log.i("Resolver", "Widevine endpoint selected (valid for ${expiresAt - now}s)")
        return resolved
    }

    suspend fun requestLicense(context: Context, spclient: String, body: ByteArray): ByteArray? = licenseRequests.withLock {
        waitForLicense()
        val url = licenseEndpoint(context, spclient)
        var token = TokenMinter.ensure(context)
        if (token.isBlank()) return@withLock null
        var response = Net.postBlob(url, body, "application/octet-stream", licenseHeaders(context, token), "license")
        if (response?.code == 401) {
            token = TokenMinter.refresh(context) ?: return@withLock null
            response = Net.postBlob(url, body, "application/octet-stream", licenseHeaders(context, token), "license")
        }
        if (response?.code == 429) {
            recordLicenseLimit(response)
            throw LicenseRateLimited()
        }
        Log.i("Resolver", "Widevine license HTTP ${response?.code}")
        response?.takeIf { it.ok }?.bytes?.takeIf { it.isNotEmpty() }?.also {
            licenseRateLimitCount = 0
            licenseRetryAt = 0
        }
    }

    private fun recordLicenseLimit(response: Net.Response, step: String = "license") {
        val retryAfter = response.retryAfterSeconds()
        val description = response.body.lowercase(java.util.Locale.ROOT)
        val reason = when {
            "quota" in description -> "quota"
            "rate limit" in description || "too many requests" in description -> "rate limit"
            "unsupported" in description -> "unsupported"
            response.bytes.isEmpty() -> "empty response"
            else -> "unspecified"
        }
        val fallbackSeconds = 60L shl licenseRateLimitCount.coerceAtMost(3)
        licenseRateLimitCount = (licenseRateLimitCount + 1).coerceAtMost(3)
        // ±20 % jitter на собственный backoff: несколько устройств за общим
        // NAT не должны синхронно бить ретраями в одно мгновение. Серверный
        // Retry-After соблюдается без изменений.
        val waitSeconds = retryAfter.takeIf { it > 0 } ?: run {
            val jitter = (fallbackSeconds * 0.2 * (Math.random() * 2 - 1)).toLong()
            (fallbackSeconds + jitter).coerceAtLeast(30L)
        }
        licenseRetryAt = SystemClock.elapsedRealtime() + waitSeconds * 1_000L
        Log.w("Resolver", "Widevine $step HTTP 429; Retry-After=$retryAfter; waiting ${waitSeconds}s; reason=$reason")
    }

    private suspend fun waitForLicense() {
        val waitMs = licenseRetryDelayMs()
        if (waitMs > 10_000L) throw LicenseRateLimited()
        if (waitMs > 0) delay(waitMs)
    }
}
