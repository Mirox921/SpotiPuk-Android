package com.project.puk.jam

import com.project.puk.core.Lang

import org.json.JSONArray
import org.json.JSONObject

/**
 * Client-side mirror of the Jam server protocol (schema v1). Parsing is
 * defensive on purpose: unknown/absent fields must never crash playback
 * (AGENTS.md rules; the server is the only source of truth).
 */
data class JamParticipant(
    val id: String,
    val name: String,
    val avatarUrl: String,
    val accountId: String,
    val role: String, // HOST | GUEST
    val connection: String, // CONNECTED | DISCONNECTED
    val readyState: String, // IDLE | PREPARING | READY | ERROR
    val readyDetail: String,
    val rttMs: Long,
) {
    val isHost: Boolean get() = role == "HOST"
    val isConnected: Boolean get() = connection == "CONNECTED"

    companion object {
        fun parse(o: JSONObject): JamParticipant = JamParticipant(
            id = o.optString("id"),
            name = o.optString("name"),
            avatarUrl = o.optString("avatarUrl"),
            accountId = o.optString("accountId"),
            role = o.optString("role", "GUEST"),
            connection = o.optString("connection", "DISCONNECTED"),
            readyState = o.optString("readyState", "IDLE"),
            readyDetail = o.optString("readyDetail"),
            rttMs = o.optLong("rttMs", 0L),
        )
    }
}

data class JamQueueEntry(
    val itemId: String,
    val uri: String,
    val title: String,
    val artist: String,
    val imageUrl: String,
    val durationMs: Long,
    val addedBy: String,
    /** Origin: playlist URI, "liked" or the track URI for single adds. */
    val sourceRef: String = "",
) {
    companion object {
        fun parse(o: JSONObject): JamQueueEntry = JamQueueEntry(
            itemId = o.optString("itemId"),
            uri = o.optString("uri"),
            title = o.optString("title"),
            artist = o.optString("artist"),
            imageUrl = o.optString("imageUrl"),
            durationMs = o.optLong("durationMs", 0L),
            addedBy = o.optString("addedBy"),
            sourceRef = o.optString("sourceRef"),
        )
    }
}

data class JamPlayback(
    val state: String, // IDLE | PREPARING | PLAYING | PAUSED
    val itemId: String?,
    val uri: String,
    val index: Int,
    val positionMs: Long,
    val speed: Double,
    val stateVersion: Long,
    val queueVersion: Long,
    val startedAtServerMs: Long,
    val updatedAt: Long,
) {
    companion object {
        fun parse(o: JSONObject): JamPlayback = JamPlayback(
            state = o.optString("state", "IDLE"),
            itemId = o.optString("itemId").takeIf { it.isNotBlank() && it != "null" },
            uri = o.optString("uri"),
            index = o.optInt("index", -1),
            positionMs = o.optLong("positionMs", 0L),
            speed = o.optDouble("speed", 1.0),
            stateVersion = o.optLong("stateVersion", 1L),
            queueVersion = o.optLong("queueVersion", 1L),
            startedAtServerMs = o.optLong("startedAtServerMs", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
        )
    }
}

/**
 * Legacy wire flags from Jam 1.0; since the equal-rights round
 * (2026-11-20) the server grants every participant full music control
 * regardless of them, so clients must not gate on them either. Only the
 * SOLO-mode choice (and kick/end/revoke) stays host-only.
 */
data class JamPermissions(
    val guestsCanAdd: Boolean = true,
    val guestsCanRemoveOwn: Boolean = true,
    val guestsCanEditQueue: Boolean = false,
    val guestsCanControlPlayback: Boolean = false,
) {
    companion object {
        fun parse(o: JSONObject): JamPermissions = JamPermissions(
            guestsCanAdd = o.optBoolean("guestsCanAdd", true),
            guestsCanRemoveOwn = o.optBoolean("guestsCanRemoveOwn", true),
            guestsCanEditQueue = o.optBoolean("guestsCanEditQueue", false),
            guestsCanControlPlayback = o.optBoolean("guestsCanControlPlayback", false),
        )
    }
}

data class JamSessionState(
    val sessionId: String,
    val inviteCode: String,
    val inviteExpiresAt: Long,
    val inviteRevoked: Boolean,
    val maxParticipants: Int,
    val permissions: JamPermissions,
    /** SYNC = каждый играет локально; SOLO = играет выбранный участник. */
    val playMode: String = "SYNC",
    val soloParticipantId: String? = null,
    val shuffleUpcoming: Boolean = false,
    val participants: List<JamParticipant>,
    /** Ушедшие/исключённые: их имена остаются у «добавил: …» (v1.7 сервер). */
    val alumni: List<JamParticipant> = emptyList(),
    val queue: List<JamQueueEntry>,
    val playback: JamPlayback,
    val myParticipantId: String,
) {
    val host: JamParticipant? get() = participants.firstOrNull { it.isHost }
    fun participant(id: String): JamParticipant? = participants.firstOrNull { it.id == id }
    fun me(): JamParticipant? = participant(myParticipantId)

    /** Имя автора трека: активный участник, затем ушедший (alumni). */
    fun participantName(id: String): String? =
        participant(id)?.name ?: alumni.firstOrNull { it.id == id }?.name

    // Equal music-control rights for every participant (owner request
    // 2026-11-20): play/pause/seek/track choice/next/prev/queue edits. The
    // server still enforces them; the host-only boundary is who plays
    // (UPDATE_MODE) plus kick/end/revoke.
    val canControlPlayback: Boolean get() = true
    val canAddTracks: Boolean get() = true
    val canEditQueue: Boolean get() = true

    companion object {
        fun parse(o: JSONObject, myParticipantId: String): JamSessionState = JamSessionState(
            sessionId = o.optString("sessionId"),
            inviteCode = o.optString("inviteCode"),
            inviteExpiresAt = o.optLong("inviteExpiresAt", 0L),
            inviteRevoked = o.optBoolean("inviteRevoked", false),
            maxParticipants = o.optInt("maxParticipants", 8),
            permissions = JamPermissions.parse(o.optJSONObject("permissions") ?: JSONObject()),
            playMode = o.optString("playMode", "SYNC"),
            soloParticipantId = o.optString("soloParticipantId").takeIf { it.isNotBlank() && it != "null" },
            shuffleUpcoming = o.optBoolean("shuffleUpcoming", false),
            participants = o.optJSONArray("participants")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(JamParticipant::parse) }
            } ?: emptyList(),
            alumni = o.optJSONArray("alumni")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(JamParticipant::parse) }
            } ?: emptyList(),
            queue = o.optJSONArray("queue")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(JamQueueEntry::parse) }
            } ?: emptyList(),
            playback = JamPlayback.parse(o.optJSONObject("playback") ?: JSONObject()),
            myParticipantId = myParticipantId,
        )
    }
}

/** Envelope shared with the server; [seq] guards against stale events. */
data class JamEnvelope(
    val type: String,
    val eventId: String? = null,
    val seq: Long? = null,
    val ts: Long? = null,
    val payload: JSONObject = JSONObject(),
) {
    fun encode(): String = JSONObject().apply {
        put("v", 1)
        put("type", type)
        eventId?.let { put("eventId", it) }
        seq?.let { put("seq", it) }
        ts?.let { put("ts", it) }
        put("payload", payload)
    }.toString()

    companion object {
        const val SCHEMA_VERSION = 1

        // Commands (client → server)
        const val CMD_AUTH = "AUTH"
        const val CMD_PING = "PING"
        const val CMD_ADD_TRACK = "ADD_TRACK"
        const val CMD_REMOVE_TRACK = "REMOVE_TRACK"
        const val CMD_REMOVE_SOURCE = "REMOVE_SOURCE"
        const val CMD_MOVE_TRACK = "MOVE_TRACK"
        const val CMD_PLAY = "PLAY"
        const val CMD_PAUSE = "PAUSE"
        const val CMD_SEEK = "SEEK"
        const val CMD_NEXT = "NEXT"
        const val CMD_PREV = "PREV"
        const val CMD_TRACK_ENDED = "TRACK_ENDED"
        const val CMD_SYNC_REPORT = "SYNC_REPORT"
        const val CMD_UPDATE_PERMISSIONS = "UPDATE_PERMISSIONS"
        const val CMD_ADD_PLAYLIST = "ADD_PLAYLIST"
        const val CMD_SHUFFLE = "SHUFFLE"
        const val CMD_UPDATE_MODE = "UPDATE_MODE"
        const val CMD_TRANSFER_HOST = "TRANSFER_HOST"
        const val CMD_KICK = "KICK"
        const val CMD_REVOKE_INVITE = "REVOKE_INVITE"
        const val CMD_END_SESSION = "END_SESSION"
        const val CMD_LEAVE = "LEAVE"

        // Events (server → client)
        const val EV_AUTH_OK = "AUTH_OK"
        const val EV_AUTH_FAILED = "AUTH_FAILED"
        const val EV_SNAPSHOT = "SNAPSHOT"
        const val EV_PARTICIPANT_JOINED = "PARTICIPANT_JOINED"
        const val EV_PARTICIPANT_LEFT = "PARTICIPANT_LEFT"
        const val EV_PARTICIPANT_UPDATED = "PARTICIPANT_UPDATED"
        const val EV_KICKED = "KICKED"
        const val EV_QUEUE_UPDATED = "QUEUE_UPDATED"
        const val EV_PLAYBACK_PREPARE = "PLAYBACK_PREPARE"
        const val EV_PLAYBACK_PLAY = "PLAYBACK_PLAY"
        const val EV_PLAYBACK_PAUSE = "PLAYBACK_PAUSE"
        const val EV_PLAYBACK_SEEK = "PLAYBACK_SEEK"
        const val EV_PERMISSIONS_UPDATED = "PERMISSIONS_UPDATED"
        const val EV_MODE_UPDATED = "MODE_UPDATED"
        const val EV_HOST_CHANGED = "HOST_CHANGED"
        const val EV_SESSION_ENDED = "SESSION_ENDED"
        const val EV_PONG = "PONG"
        const val EV_ERROR = "ERROR"

        fun decode(text: String): JamEnvelope? = runCatching {
            val o = JSONObject(text)
            // The server encodes defaults, so null-valued keys are present:
            // "seq":null must read as absent, not crash getLong.
            JamEnvelope(
                type = o.optString("type"),
                eventId = if (o.isNull("eventId")) null else o.optString("eventId").ifBlank { null },
                seq = if (!o.isNull("seq")) o.getLong("seq") else null,
                ts = if (!o.isNull("ts")) o.getLong("ts") else null,
                payload = o.optJSONObject("payload") ?: JSONObject(),
            )
        }.getOrNull()
    }
}

/** Queue/payload helpers shared by UI and controller. */
fun JSONArray.toStringList(): List<String> = (0 until length()).mapNotNull { optString(it) }

/** Only music tracks belong in Jam 1.0 (owner correction: podcasts out). */
val TRACK_URI_REGEX = Regex("""spotify:track:[A-Za-z0-9]{22}""")
fun isSupportedTrackUri(uri: String): Boolean = TRACK_URI_REGEX.matches(uri)

/**
 * Human-readable join/invite errors: the REST layer answers with a machine
 * `error` code that must not surface as raw English (expired/revoked/missing
 * invites and an unreachable server are the common owner-reported cases).
 */
internal class JamJoinException(private val errorCode: String, private val fallback: String? = null) : IllegalStateException() {
    override val message: String get() = jamJoinErrorMessage(errorCode, fallback)
}

fun jamJoinErrorMessage(errorCode: String, fallback: String?): String = when (errorCode) {
    "server_unavailable" -> Lang.t(Lang.Key.JAM_SERVER_UNAVAILABLE)
    "session_not_found" -> Lang.t(Lang.Key.JAM_INVITE_NOT_FOUND)
    "invite_expired" -> Lang.t(Lang.Key.JAM_INVITE_EXPIRED)
    "invite_revoked" -> Lang.t(Lang.Key.JAM_INVITE_REVOKED)
    "session_full" -> Lang.t(Lang.Key.JAM_SESSION_FULL)
    "rate_limited" -> Lang.t(Lang.Key.JAM_TOO_MANY_ATTEMPTS)
    else -> fallback?.takeIf { it.isNotBlank() } ?: Lang.t(Lang.Key.JAM_JOIN_ERROR_GENERIC)
}
