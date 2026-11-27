package com.project.puk.jam

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * Holds the server-mirrored Jam state with version guards (owner correction
 * #1): stale events are dropped, queue replaces only on queueVersion changes,
 * playback applies only when stateVersion advances.
 */
class JamSession {

    data class UiState(
        val session: JamSessionState? = null,
        val connection: JamClient.ConnectionState = JamClient.ConnectionState.OFFLINE,
        /** True when the local player already mirrors the server queue. */
        val queueLoaded: Boolean = false,
        /** Server playback state mapped onto the local clock (ms from now). */
        val expectedPositionMs: Long = 0,
        val syncing: Boolean = false,
        /**
         * Добавленные партии: sourceRef (playlist URI / "liked") → сколько
         * треков было запрошено. Держит постоянную отметку «Добавлен» в меню
         * добавления музыки; сопоставляется с реальным составом очереди.
         */
        val addedPartitions: Map<String, Int> = emptyMap(),
        /** Партии, отправка которых ещё выполняется (статус «Добавляется…»). */
        val addingRefs: Set<String> = emptySet(),
        /** Одноразовое сообщение сервера для тоста (queue_partial и др.). */
        val notice: String? = null,
    )

    val ui = MutableStateFlow(UiState())
    val sessionState: StateFlow<UiState> get() = ui

    /** Clock sync from PING/PONG samples (robust median of the recent window). */
    @Volatile
    var offset: JamSync.Sample? = null
        private set

    private val samples = mutableListOf<JamSync.Sample>()

    // Version guards (correction #1).
    @Volatile
    var appliedStateVersion: Long = 0
        private set

    @Volatile
    var appliedQueueVersion: Long = 0
        private set

    /** elapsedRealtime when [appliedPositionMs] was authoritative. */
    @Volatile
    var appliedPositionAtElapsedMs: Long = 0

    @Volatile
    var appliedPositionMs: Long = 0

    @Volatile
    var appliedPlaying: Boolean = false

    /** elapsedRealtime of the authoritative start moment for the current track. */
    @Volatile
    var startAtElapsedMs: Long = 0

    // Wall-jump guard: elapsed is monotonic, the wall clock is not (NTP/NITZ
    // corrections can jump seconds mid-session and silently corrupt every
    // position anchor derived from server wall time).
    private var wallAtAnchorMs: Long = 0
    private var elapsedAtAnchorMs: Long = 0

    /**
     * Monotonic local clock. Hook instead of a hard SystemClock call so the
     * pure-JVM unit tests (android.jar stubs throw) can substitute a clock.
     */
    internal var clock: () -> Long = SystemClock::elapsedRealtime

    /**
     * Имена участников, увиденные за жизнь сессии: треки ушедших продолжают
     * показывать «добавил: имя» даже на серверах без alumni-ростера.
     */
    private val nameCache = HashMap<String, String>()

    fun cachedName(id: String): String? = nameCache[id]

    private fun rememberName(p: JamParticipant) {
        if (p.id.isNotBlank() && p.name.isNotBlank()) nameCache[p.id] = p.name
    }

    private fun rebaseAnchor() {
        wallAtAnchorMs = System.currentTimeMillis()
        elapsedAtAnchorMs = clock()
    }

    /**
     * True (once) when the wall clock moved differently than the monotonic
     * clock since the last anchor — a clock correction mid-session. [offset]
     * is invalidated so the controller re-runs the clock burst; elapsed-based
     * position anchors stay valid (elapsed never jumps).
     */
    fun checkWallJump(): Boolean {
        val wallDelta = System.currentTimeMillis() - wallAtAnchorMs
        val elapsedDelta = clock() - elapsedAtAnchorMs
        if (kotlin.math.abs(wallDelta - elapsedDelta) <= 1_500L) return false
        rebaseAnchor()
        synchronized(samples) {
            samples.clear()
            offset = null
        }
        return true
    }

    /**
     * Ожидаемая позиция трека на монотонных часах. Якорь — момент СТАРТА
     * (startAtElapsedMs), а не момент получения PLAY-кадра: раньше ожидание
     * «убегало» на lead (0,7–1 c) сразу после кадра, монитор дрифта видел
     * ложный гэп и никогда не корректировал реальный рассинхрон старта.
     */
    fun expectedPosition(): Long {
        val state = ui.value.session ?: return 0
        val playback = state.playback
        if (playback.state != "PLAYING") return playback.positionMs
        val anchor = if (startAtElapsedMs > 0) startAtElapsedMs else appliedPositionAtElapsedMs
        val elapsed = (clock() - anchor).coerceAtLeast(0)
        return playback.positionMs + (elapsed * playback.speed).toLong()
    }

    fun applyServerTime(sample: JamSync.Sample) {
        rebaseAnchor()
        synchronized(samples) {
            samples.add(sample)
            while (samples.size > 8) samples.removeAt(0)
            offset = JamSync.offsetOf(samples)
        }
    }

    fun reset() {
        ui.value = UiState()
        appliedStateVersion = 0
        appliedQueueVersion = 0
        appliedPlaying = false
        appliedPositionMs = 0
        startAtElapsedMs = 0
        synchronized(samples) {
            samples.clear()
            offset = null
        }
        rebaseAnchor()
    }

    /** Registers a bulk add (playlist): mark + «Добавляется…» until confirmed. */
    fun registerPartition(sourceRef: String, requested: Int) {
        if (sourceRef.isBlank()) return
        val current = ui.value
        ui.value = current.copy(
            addedPartitions = current.addedPartitions + (sourceRef to requested),
            addingRefs = current.addingRefs + sourceRef,
        )
    }

    /** QUEUE_UPDATED с sourceRef подтвердил доставку партии на сервер. */
    fun finishAdding(sourceRef: String) {
        if (sourceRef.isBlank()) return
        ui.value = ui.value.copy(addingRefs = ui.value.addingRefs - sourceRef)
    }

    /** Ошибка добавления: убрать «Добавляется…», показать реальную ошибку. */
    fun failAdding(sourceRef: String, message: String) {
        val current = ui.value
        ui.value = current.copy(
            addingRefs = if (sourceRef.isBlank()) current.addingRefs else current.addingRefs - sourceRef,
            notice = message,
        )
    }

    /**
     * Возраст снапшота в полёте: сервер ставит serverNowMs перед отправкой.
     * С известным offset вычитаем его — иначе скачок локальных часов
     * (wall skew) целиком попадёт в «возраст» и якорь уедет.
     */
    private fun snapshotInFlightMs(serverNow: Long): Long {
        val raw = System.currentTimeMillis() - serverNow - (offset?.offsetMs ?: 0L)
        return raw.coerceIn(0L, 2_000L)
    }

    fun applySnapshot(payload: JSONObject, myParticipantId: String): JamSessionState {
        val sessionObj = payload.optJSONObject("session") ?: JSONObject()
        val state = JamSessionState.parse(sessionObj, payload.optString("participantId", myParticipantId))
        appliedStateVersion = state.playback.stateVersion
        appliedQueueVersion = state.playback.queueVersion
        // Anchor on the server-computed effective position when present: the
        // stored playback.positionMs is the value at updatedAt, so a guest
        // reconnecting mid-song used to restore 0:00 while the track was at
        // 2:18 (owner bug report). serverNowMs lets us anchor without waiting
        // for the first PONG.
        val effective = payload.optLong("effectivePositionMs", -1L)
        val serverNow = payload.optLong("serverNowMs", 0L)
        val anchored = if (effective >= 0 && serverNow > 0) {
            effective + snapshotInFlightMs(serverNow)
        } else if (effective >= 0) effective
        else state.playback.positionMs
        rebaseAnchor()
        appliedPositionMs = anchored
        appliedPlaying = state.playback.state == "PLAYING"
        appliedPositionAtElapsedMs = clock()
        startAtElapsedMs = if (state.playback.state == "PLAYING") {
            // Mid-song join: the start moment already passed, so "now" is the
            // anchor; PLAY events later overwrite it with startAtServerMs.
            appliedPositionAtElapsedMs
        } else 0L
        state.participants.forEach(::rememberName)
        state.alumni.forEach(::rememberName)
        ui.value = ui.value.copy(session = state.copy(playback = state.playback.copy(positionMs = anchored)), queueLoaded = false)
        return ui.value.session!!
    }

    /** QUEUE_UPDATED: replace the queue copy only when the version advanced. */
    fun applyQueueUpdated(payload: JSONObject): JamSessionState? {
        val current = ui.value.session ?: return null
        val version = payload.optLong("queueVersion", current.playback.queueVersion)
        if (version <= appliedQueueVersion) return null // stale (correction #1)
        val sessionObj = payload.optJSONObject("session")
        val updated = if (sessionObj != null) {
            JamSessionState.parse(sessionObj, current.myParticipantId)
        } else {
            current
        }
        appliedQueueVersion = version
        ui.value = ui.value.copy(session = updated)
        return updated
    }

    /** Playback events: only fresher state versions are applied. */
    fun applyPlaybackUpdate(
        payload: JSONObject,
        newState: String,
        positionMs: Long?,
        startAtServerMs: Long?,
    ): JamPlayback? {
        val current = ui.value.session ?: return null
        val version = payload.optLong("stateVersion", current.playback.stateVersion + 1)
        if (version < appliedStateVersion) return null // stale (correction #1)
        val playback = current.playback.copy(
            state = newState,
            itemId = payload.optString("itemId").takeIf { it.isNotBlank() && it != "null" }
                ?: current.playback.itemId,
            uri = payload.optString("uri").ifBlank { current.playback.uri },
            index = if (payload.has("index")) payload.optInt("index", current.playback.index) else current.playback.index,
            positionMs = positionMs ?: current.playback.positionMs,
            stateVersion = version,
            startedAtServerMs = startAtServerMs ?: current.playback.startedAtServerMs,
            updatedAt = System.currentTimeMillis(),
        )
        appliedStateVersion = version
        appliedPositionMs = playback.positionMs
        appliedPlaying = playback.state == "PLAYING"
        rebaseAnchor()
        appliedPositionAtElapsedMs = clock()
        if (startAtServerMs != null && startAtServerMs > 0) {
            // Map the authoritative server start moment onto the LOCAL
            // MONOTONIC clock: startAtElapsed = now + (startAtServer − serverNow).
            val serverNow = JamSync.serverTimeNow(offset?.offsetMs, System.currentTimeMillis())
            startAtElapsedMs = clock() + (startAtServerMs - serverNow)
        } else if (newState == "PLAYING") {
            startAtElapsedMs = clock()
        }
        ui.value = ui.value.copy(session = current.copy(playback = playback))
        return playback
    }

    fun applyMode(playMode: String, soloParticipantId: String? = null): JamSessionState? {
        val current = ui.value.session ?: return null
        ui.value = ui.value.copy(session = current.copy(playMode = playMode, soloParticipantId = soloParticipantId))
        return ui.value.session
    }

    fun applyPermissions(payload: JSONObject): JamSessionState? {
        val current = ui.value.session ?: return null
        val permissions = JamPermissions.parse(payload)
        ui.value = ui.value.copy(session = current.copy(permissions = permissions))
        return ui.value.session
    }

    fun applyParticipant(payload: JSONObject, event: String): JamSessionState? {
        val current = ui.value.session ?: return null
        val participant = payload.optJSONObject("participant")?.let(JamParticipant::parse) ?: return null
        rememberName(participant)
        val participants = current.participants.filterNot { it.id == participant.id }.toMutableList()
        if (event != JamEnvelope.EV_PARTICIPANT_LEFT && event != JamEnvelope.EV_KICKED) {
            participants.add(participant)
        } else {
            ui.value = ui.value.copy(session = current.copy(participants = participants))
            return ui.value.session
        }
        ui.value = ui.value.copy(session = current.copy(participants = participants))
        return ui.value.session
    }

    /** PARTICIPANT_UPDATED replaces an existing entry in place. */
    fun replaceParticipant(participant: JamParticipant): JamSessionState? {
        val current = ui.value.session ?: return null
        rememberName(participant)
        val participants = current.participants.map { if (it.id == participant.id) participant else it }
        ui.value = ui.value.copy(session = current.copy(participants = participants))
        return ui.value.session
    }

    fun applyHostChanged(newHostId: String): JamSessionState? {
        val current = ui.value.session ?: return null
        val participants = current.participants.map {
            when (it.id) {
                newHostId -> it.copy(role = "HOST")
                else -> it.copy(role = "GUEST")
            }
        }
        ui.value = ui.value.copy(session = current.copy(participants = participants))
        return ui.value.session
    }
}
