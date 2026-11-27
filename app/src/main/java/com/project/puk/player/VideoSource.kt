package com.project.puk.player

import android.content.Context
import com.project.puk.core.Auth
import com.project.puk.core.ClientTokenManager
import com.project.puk.core.Log
import com.project.puk.core.Net
import com.project.puk.core.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * Resolves Spotify video-episode sources and materialises a local DASH
 * manifest. The web-player endpoint hands out CMAF segment templates rather
 * than a ready MPD, so the playable description is assembled locally from
 * the received profiles, base URLs and timing — no endpoint probing beyond
 * the same track-playback/manifests calls the audio pipeline already uses.
 *
 * Availability is decided by the data: an episode without a video id in the
 * media response simply has no video source, and nothing video-related is
 * shown for it.
 */
object VideoSource {

    private const val MEDIA_PATH = "https://%s/track-playback/v1/media/%s?manifestFileFormat=file_ids_mp4&manifest"
    private const val MANIFEST_PATH = "https://%s/manifests/v9/json/sources/%s/options/supports_drm"
    private const val FALLBACK_SPCLIENT = "guc3-spclient.spotify.com"
    private const val FALLBACK_LICENSE = "/widevine-license/v1/video/license"

    data class VideoProfile(
        val id: String,
        val mime: String,
        val codec: String,
        val bitrate: Long,
        val width: Int,
        val height: Int,
        val fileType: String,
    )

    data class VideoManifestData(
        val videoId: String,
        val baseUrls: List<String>,
        val initializationTemplate: String,
        val segmentTemplate: String,
        val segmentLengthSeconds: Int,
        val startMs: Long,
        val endMs: Long,
        val videoProfiles: List<VideoProfile>,
        val audioProfiles: List<VideoProfile>,
        val licenseServer: String?, // relative or absolute; null → plain fallback path
        val pssh: String?,
        val keyId: String?,
    ) {
        val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
        val isEncrypted: Boolean get() = pssh != null
    }

    /** Ids whose video probe failed recently — avoids hammering the endpoint. */
    private val negativeCache = HashMap<String, Long>()
    private const val NEGATIVE_TTL_MS = 10 * 60_000L

    suspend fun spclientHost(context: Context): String = SourceResolver.spclient(context)

    private suspend fun playHeaders(context: Context): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${Auth.token(context)}",
        "client-token" to ClientTokenManager.get(context),
        "app-platform" to "WebPlayer",
        "spotify-app-version" to Net.WEB_PLAYER_VERSION,
        "User-Agent" to Net.DESKTOP_UA,
        "Accept" to "*/*",
    )

    /**
     * Looks up the video source id of an episode through the media endpoint.
     * Returns null when the episode carries no video (cached for a while).
     */
    suspend fun episodeVideoId(context: Context, episodeUri: String): String? {
        negativeCache[episodeUri]?.let { at -> if (System.currentTimeMillis() - at < NEGATIVE_TTL_MS) return null }
        val host = spclientHost(context)
        val response = Net.get(
            MEDIA_PATH.format(host, episodeUri),
            playHeaders(context),
            "video-id",
        )
        val media = response?.json()?.optJSONObject("media") ?: run {
            negativeCache[episodeUri] = System.currentTimeMillis()
            return null
        }
        val item = media.optJSONObject(episodeUri)?.optJSONObject("item")
            ?: media.keys().asSequence().mapNotNull { media.optJSONObject(it) }.firstOrNull()?.optJSONObject("item")
        val videoId = item?.optString("video_id")?.takeIf { it.isNotBlank() }
            ?: item?.optJSONObject("manifest")?.optJSONArray("manifest_ids_video")
                ?.optJSONObject(0)?.optString("file_id")?.takeIf { it.isNotBlank() }
        if (videoId == null) {
            negativeCache[episodeUri] = System.currentTimeMillis()
            Log.i("Video", "no video id for ${episodeUri.takeLast(12)}")
        }
        return videoId
    }

    suspend fun resolveManifest(context: Context, videoId: String): VideoManifestData? {
        val host = spclientHost(context)
        val response = Net.get(
            MANIFEST_PATH.format(host, videoId),
            playHeaders(context),
            "video-manifest",
        )
        val json = response?.takeIf { it.ok }?.json() ?: run {
            Log.w("Video", "manifest ${response?.code ?: "no response"} for ${videoId.take(12)}")
            return null
        }
        val contents = json.optJSONArray("contents")?.optJSONObject(0) ?: return null
        // base_urls and the segment templates live on the manifest's top level;
        // contents carries timing, profiles and encryption only.
        val baseUrls = mutableListOf<String>()
        json.optJSONArray("base_urls")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(baseUrls::add)
        }
        if (baseUrls.isEmpty()) {
            Log.w("Video", "manifest without base_urls")
            return null
        }
        val initTemplate = json.optString("initialization_template")
        val segmentTemplate = json.optString("segment_template")
        if (initTemplate.isBlank() || segmentTemplate.isBlank()) {
            Log.w("Video", "manifest without segment templates")
            return null
        }
        val videoProfiles = mutableListOf<VideoProfile>()
        val audioProfiles = mutableListOf<VideoProfile>()
        contents.optJSONArray("profiles")?.let { profiles ->
            for (i in 0 until profiles.length()) {
                val node = profiles.optJSONObject(i) ?: continue
                val mime = node.optString("mime_type")
                val codec = node.optString("video_codec").ifBlank { node.optString("audio_codec") }
                val profile = VideoProfile(
                    id = node.optString("id"),
                    mime = mime,
                    codec = codec,
                    bitrate = node.optLong("max_bitrate", node.optLong("video_bitrate", node.optLong("audio_bitrate", 0L))),
                    width = node.optInt("video_width", 0),
                    height = node.optInt("video_height", 0),
                    fileType = node.optString("file_type").ifBlank { "mp4" },
                )
                when {
                    // Only AVC/H264 video and AAC audio: mixing WebM/Opus
                    // variants into one MP4-based AdaptationSet breaks DASH
                    // segment addressing (their inits live under other paths).
                    mime.startsWith("video/") && codec.startsWith("avc") -> videoProfiles.add(profile)
                    mime.startsWith("audio/") && codec.startsWith("mp4a") -> audioProfiles.add(profile)
                }
            }
        }
        if (videoProfiles.isEmpty() || audioProfiles.isEmpty()) {
            Log.w("Video", "manifest profiles video=${videoProfiles.size} audio=${audioProfiles.size}")
            return null
        }
        videoProfiles.sortByDescending { it.height }
        var licenseServer: String? = null
        var pssh: String? = null
        var keyId: String? = null
        contents.optJSONArray("encryption_infos")?.optJSONObject(0)?.let { enc ->
            if (enc.optString("key_system") == "widevine") {
                val endpoint = enc.optString("license_server_endpoint")
                licenseServer = if (endpoint.startsWith("http")) endpoint
                else if (endpoint.isNotBlank()) "https://$host/$endpoint"
                else "https://$host$FALLBACK_LICENSE"
                pssh = enc.optString("encryption_data").takeIf { it.isNotBlank() }
                keyId = enc.optString("key_id").takeIf { it.isNotBlank() }
            }
        }
        Log.i("Video", "manifest ok: video=${videoProfiles.size} audio=${audioProfiles.size} " +
            "init=${initTemplate.take(120)} media=${segmentTemplate.take(120)}")
        return VideoManifestData(
            videoId = videoId,
            baseUrls = baseUrls,
            initializationTemplate = initTemplate,
            segmentTemplate = segmentTemplate,
            segmentLengthSeconds = contents.optInt("segment_length", 4),
            startMs = contents.optLong("start_time_millis", 0L),
            endMs = contents.optLong("end_time_millis", 0L),
            videoProfiles = videoProfiles,
            audioProfiles = audioProfiles,
            licenseServer = licenseServer,
            pssh = pssh,
            keyId = keyId,
        )
    }

    /**
     * Builds a static DASH manifest on disk so ExoPlayer can consume the
     * CMAF segments through its standard DashMediaSource.
     */
    suspend fun buildDashFile(context: Context, manifest: VideoManifestData): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "video-manifests").apply { mkdirs() }
            val file = File(dir, "ep-${manifest.videoId.take(16)}.mpd")
            file.writeText(buildMpd(manifest))
            file
        } catch (e: Exception) {
            Log.w("Video", "mpd build failed: ${e.message}")
            null
        }
    }

    fun buildMpd(manifest: VideoManifestData): String {
        // CDN segment timestamps are in SECONDS (timescale=1), matching the
        // server's segment_length unit. Verified against live segment URLs.
        val durationSec = manifest.durationMs / 1000.0
        val fullSegments = (manifest.durationMs / 1000L / manifest.segmentLengthSeconds).coerceAtLeast(1)
        val remainderMs = manifest.durationMs - fullSegments * manifest.segmentLengthSeconds * 1000L
        val remainderSec = ((remainderMs + 999) / 1000).coerceAtLeast(0)
        val baseUrl = manifest.baseUrls.first()
        val init = manifest.initializationTemplate
            .replace("{{profile_id}}", "\$RepresentationID\$")
            .replace("{{file_type}}", "mp4")
        val media = manifest.segmentTemplate
            .replace("{{profile_id}}", "\$RepresentationID\$")
            .replace("{{segment_timestamp}}", "\$Time\$")
            .replace("{{file_type}}", "mp4")
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" profiles=\"urn:mpeg:dash:profile:isoff-live:2011\" ")
            .append("type=\"static\" mediaPresentationDuration=\"PT").append(trimSeconds(durationSec)).append("S\" ")
            .append("minBufferTime=\"PT2S\">\n")
        sb.append("  <Period start=\"PT0S\">\n")
        sb.append("    <BaseURL>").append(escape(baseUrl)).append("</BaseURL>\n")
        sb.append("    <AdaptationSet id=\"0\" mimeType=\"video/mp4\" contentType=\"video\" segmentAlignment=\"true\">\n")
        appendProtection(sb, manifest)
        sb.append("      <SegmentTemplate timescale=\"1\" duration=\"")
            .append(manifest.segmentLengthSeconds)
            .append("\" initialization=\"").append(escape(baseUrl + init))
            .append("\" media=\"").append(escape(baseUrl + media))
            .append("\" startNumber=\"1\">\n")
        sb.append("        <SegmentTimeline>\n")
        sb.append("          <S t=\"0\" d=\"").append(manifest.segmentLengthSeconds)
            .append("\" r=\"").append(fullSegments - 1).append("\"/>\n")
        if (remainderSec > 0) sb.append("          <S d=\"").append(remainderSec).append("\"/>\n")
        sb.append("        </SegmentTimeline>\n")
        sb.append("      </SegmentTemplate>\n")
        manifest.videoProfiles.forEach { profile ->
            sb.append("      <Representation id=\"").append(escape(profile.id))
                .append("\" codecs=\"").append(escape(profile.codec))
                .append("\" mimeType=\"").append(profile.mime)
                .append("\" bandwidth=\"").append(profile.bitrate.coerceAtLeast(1))
                .append("\" width=\"").append(profile.width)
                .append("\" height=\"").append(profile.height)
                .append("\" />\n")
        }
        sb.append("    </AdaptationSet>\n")
        sb.append("    <AdaptationSet id=\"1\" mimeType=\"audio/mp4\" contentType=\"audio\" segmentAlignment=\"true\">\n")
        appendProtection(sb, manifest)
        sb.append("      <SegmentTemplate timescale=\"1\" duration=\"")
            .append(manifest.segmentLengthSeconds)
            .append("\" initialization=\"").append(escape(baseUrl + init))
            .append("\" media=\"").append(escape(baseUrl + media))
            .append("\" startNumber=\"1\">\n")
        sb.append("        <SegmentTimeline>\n")
        sb.append("          <S t=\"0\" d=\"").append(manifest.segmentLengthSeconds)
            .append("\" r=\"").append(fullSegments - 1).append("\"/>\n")
        if (remainderSec > 0) sb.append("          <S d=\"").append(remainderSec).append("\"/>\n")
        sb.append("        </SegmentTimeline>\n")
        sb.append("      </SegmentTemplate>\n")
        manifest.audioProfiles.forEach { profile ->
            sb.append("      <Representation id=\"").append(escape(profile.id))
                .append("\" codecs=\"").append(escape(profile.codec))
                .append("\" mimeType=\"").append(profile.mime)
                .append("\" bandwidth=\"").append(profile.bitrate.coerceAtLeast(1))
                .append("\" audioSamplingRate=\"48000\">\n")
            sb.append("        <AudioChannelConfiguration schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\" value=\"2\" />\n")
            sb.append("      </Representation>\n")
        }
        sb.append("    </AdaptationSet>\n")
        sb.append("  </Period>\n")
        sb.append("</MPD>\n")
        return sb.toString()
    }

    private fun appendProtection(sb: StringBuilder, manifest: VideoManifestData) {
        if (!manifest.isEncrypted) return
        val kidUuid = manifest.keyId?.let { keyId ->
            runCatching {
                val raw = Base64.getDecoder().decode(keyId)
                val hex = raw.joinToString("") { "%02x".format(it) }
                listOf(8, 10, 12, 14).fold(hex) { acc, at -> acc.substring(0, at) + "-" + acc.substring(at) }
            }.getOrNull()
        }
        sb.append("      <ContentProtection schemeIdUri=\"urn:mpeg:dash:mp4-protection:2011\" value=\"cenc\"")
        if (kidUuid != null) sb.append(" cenc:default_KID=\"").append(kidUuid).append("\"")
        sb.append(" />\n")
        sb.append("      <ContentProtection schemeIdUri=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\">\n")
        if (manifest.pssh != null) {
            sb.append("        <cenc:pssh>")
                .append(Base64.getEncoder().encodeToString(manifest.pssh.toByteArray()))
                .append("</cenc:pssh>\n")
        }
        sb.append("      </ContentProtection>\n")
    }

    private fun trimSeconds(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else String.format(java.util.Locale.US, "%.3f", value)

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
