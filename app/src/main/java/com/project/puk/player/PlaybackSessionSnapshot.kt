package com.project.puk.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.project.puk.core.SecurePrefs
import org.json.JSONArray
import org.json.JSONObject

@UnstableApi
internal data class PlaybackSessionSnapshot(
    val accountId: String,
    val queue: List<PukPlayer.QueueItem>,
    val index: Int,
    val positionMs: Long,
    val durationMs: Long,
    val shuffle: PukPlayer.Shuffle,
    val repeat: PukPlayer.Repeat,
    val originalQueue: List<PukPlayer.QueueItem>?,
    val suggestions: Set<String>,
    val wasPlaying: Boolean,
    val savedAt: Long = System.currentTimeMillis(),
    val queueContext: String? = null,
) {
    fun encode(): String = JSONObject()
        .put("version", 1).put("mode", "local").put("account", accountId)
        .put("queue", encodeQueue(queue)).put("index", index)
        .put("position", positionMs).put("duration", durationMs)
        .put("shuffle", shuffle.name).put("repeat", repeat.name)
        .put("original", originalQueue?.let(::encodeQueue))
        .put("suggestions", JSONArray(suggestions.toList()))
        .put("context", queueContext).put("playing", wasPlaying).put("savedAt", savedAt)
        .toString()

    companion object {
        fun decode(raw: String, accountId: String): PlaybackSessionSnapshot? = runCatching {
            val json = JSONObject(raw)
            if (accountId.isBlank() || json.getInt("version") != 1 ||
                json.getString("mode") != "local" || json.getString("account") != accountId
            ) return null
            val queue = decodeQueue(json.getJSONArray("queue"))
            val index = json.getInt("index")
            if (index !in queue.indices) return null
            val duration = json.optLong("duration").coerceAtLeast(0)
            PlaybackSessionSnapshot(
                accountId, queue, index,
                json.optLong("position").coerceIn(0, duration.takeIf { it > 0 } ?: Long.MAX_VALUE),
                duration, PukPlayer.Shuffle.valueOf(json.getString("shuffle")),
                PukPlayer.Repeat.valueOf(json.getString("repeat")),
                json.optJSONArray("original")?.let(::decodeQueue),
                json.optJSONArray("suggestions")?.let { array ->
                    (0 until array.length()).mapTo(mutableSetOf()) { array.getString(it) }
                }.orEmpty(),
                json.optBoolean("playing"), json.optLong("savedAt"),
                json.optString("context").takeIf { it.isNotBlank() },
            )
        }.getOrNull()

        private fun encodeQueue(queue: List<PukPlayer.QueueItem>) = JSONArray().apply {
            queue.forEach {
                put(JSONObject().put("uri", it.uri).put("title", it.title)
                    .put("artist", it.artist).put("album", it.albumUri).put("image", it.imageUrl)
                    .put("duration", it.durationMs).put("artistUri", it.artistUri).put("artistUris", JSONArray(it.artistUris)))
            }
        }

        private fun decodeQueue(entries: JSONArray): List<PukPlayer.QueueItem> = List(entries.length()) { index ->
            val item = entries.getJSONObject(index)
            val uri = item.getString("uri")
            require(listOf("spotify:track:", "spotify:episode:").any { prefix ->
                uri.startsWith(prefix) && uri.length > prefix.length
            })
            PukPlayer.QueueItem(
                uri, item.optString("title"), item.optString("artist"), item.optString("album"),
                item.optString("image"), item.optLong("duration").coerceAtLeast(0), item.optString("artistUri"),
                item.optJSONArray("artistUris")?.let { artists ->
                    (0 until artists.length()).mapNotNull { artists.optString(it).takeIf { uri -> uri.startsWith("spotify:artist:") } }
                }.orEmpty(),
            )
        }
    }
}

internal class PlaybackSessionStore(private val context: Context) {
    fun read(): String? = SecurePrefs.prefs(context).getString(KEY, null)

    fun write(value: String?) {
        val editor = SecurePrefs.prefs(context).edit()
        if (value == null) editor.remove(KEY) else editor.putString(KEY, value)
        check(editor.commit())
    }

    companion object {
        const val KEY = "local_playback_snapshot_v1"
    }
}
