package com.project.puk.jam

import com.project.puk.core.Lang

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.project.puk.core.Auth
import com.project.puk.core.Net
import com.project.puk.core.SecurePrefs
import com.project.puk.player.PukPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID

/**
 * Bridges the server-mirrored Jam state and the local PukPlayer.
 *
 * Owner correction #1: playQueue() runs only on real track/queue changes
 * (SNAPSHOT and PREPARE); play/pause/seek are individual operations; stale
 * state versions never restart playback.
 * Owner correction #3: the server decides track advancement — the host
 * reports TRACK_ENDED, guests follow TRACK_CHANGED.
 * Owner correction #2: the personal queue snapshot is captured before
 * joining and restored on leave; Jam checkpoints never overwrite it.
 */
class JamController(
    private val context: Context,
    private val engine: PukPlayer,
    private val scope: CoroutineScope,
) : PukPlayer.TransportDelegate {

    companion object {
        private const val TAG = "Jam"
        /** PING cadence for clock sync; also proves liveness. */
        private const val CLOCK_SYNC_MS = 5_000L
        /** Connect-time clock burst (spec section 2): fast probes give a
         *  stable offset estimate long before the first PLAY. */
        private const val CLOCK_BURST_PROBES = 6
        private const val CLOCK_BURST_INTERVAL_MS = 150L
        /** How often drift is measured while playing. */
        private const val DRIFT_CHECK_MS = 1_000L
        /** Deterministic license-stagger cap for PREPARE (правки 1–2). */
        private const val PREPARE_STAGGER_MAX_MS = 600L
        /** Periodic sync-quality summary (JamDiagnostics). */
        private const val DIAG_SUMMARY_MS = 10_000L
        /** No server frames for this long ⇒ dead link, reconnect immediately. */
        private const val STALE_AFTER_MS = 20_000L
        /** ADD_PLAYLIST chunk size: ≤400 entries keeps the frame ≪ 256 KB. */
        private const val ADD_PLAYLIST_BATCH = 400
        /** Server confirmation window for one ADD_PLAYLIST batch. */
        private const val BATCH_CONFIRM_TIMEOUT_MS = 15_000L

        /** ERROR codes that abort an ADD_PLAYLIST batch. */
        private val ADD_FAILURE_CODES = setOf(
            "queue_full", "queue_partial", "unsupported_content",
            "rate_limited", "bad_entries", "permission_denied",
        )

        /**
         * Confirmation channel for the in-flight ADD_PLAYLIST batch: the
         * envelope handlers complete it (QUEUE_UPDATED = true, add-related
         * ERROR = false); a timeout is treated as a failure.
         */
        private var pendingBatchResult: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
        private const val KEY_SESSION = "jam_session_id"
        private const val KEY_PARTICIPANT = "jam_participant_id"
        private const val KEY_TOKEN = "jam_token"

        /**
         * Process-wide handle for the live session: PlaybackService reads it
         * to shape MediaSession commands (NEXT/PREV are host-only), the
         * engine transport delegate consumes user-initiated commands.
         */
        @Volatile
        var activeController: JamController? = null
            private set
    }

    val session = JamSession()

    /** Live engine playback state (position/duration) for the Jam UI seekbar. */
    val engineState: kotlinx.coroutines.flow.StateFlow<PukPlayer.State> get() = engine.state

    private var client: JamClient? = null
    private var controllerScope: CoroutineScope? = null

    /** Credentials for reconnects; kept in memory only. */
    var myParticipantId: String = ""
        private set
    var myToken: String = ""
        private set
    var activeSessionId: String = ""
        private set

    private val prefs get() = SecurePrefs.prefs(context)
    fun persistSession() {
        if (activeSessionId.isBlank()) return
        prefs.edit()
            .putString(KEY_SESSION, activeSessionId)
            .putString(KEY_PARTICIPANT, myParticipantId)
            .putString(KEY_TOKEN, myToken)
            .apply()
    }

    fun clearPersistedSession() {
        prefs.edit()
            .remove(KEY_SESSION).remove(KEY_PARTICIPANT).remove(KEY_TOKEN)
            .apply()
    }

    /** True when EncryptedPrefs hold a Jam session worth reconnecting to. */
    fun hasPersistedSession(): Boolean =
        prefs.getString(KEY_SESSION, null)?.isNotBlank() == true &&
        prefs.getString(KEY_TOKEN, null)?.isNotBlank() == true

    private var clockSyncJob: Job? = null
    private var driftJob: Job? = null
    private var engineEventsJob: Job? = null
    private var engineStateJob: Job? = null
    private var stalenessJob: Job? = null
    private var scheduledStartJob: Job? = null
    private var pendingPrepareReported = false
    /** Подряд идущие замеры с гэпом ≥ NUDGE_IGNORE_MS (окно подтверждения). */
    private var driftStreak = 0
    /** Тонкая коррекция скоростью активна (spec раздел 5). */
    private var nudging = false
    /** elapsedRealtime последней seek-коррекции (cooldown против пинг-понга). */
    private var lastSeekAtElapsedMs = 0L
    /** Кэш локальной аудио-задержки; маршрут отслеживает [routeWatcher]. */
    private var audioLatencyMs = 0L
    private var routeWatcher: AudioLatency.RouteWatcher? = null
    private var diagJob: Job? = null
    private var prepareStartedAtElapsedMs = 0L
    private var prepareUri = ""

    /** Artwork lookups already requested for this session (dedupe). */
    private val artworkRequested = mutableSetOf<String>()

    /** True after leaving while Jam audio kept playing: offer the restore. */
    var personalRestorePending: Boolean = false
        private set

    /** Server URL from BuildConfig (Settings can override later). */
    private val serverUrl: String = com.project.puk.BuildConfig.JAM_SERVER_URL

    val isActive: Boolean get() = activeSessionId.isNotBlank()

    /**
     * Restores a Jam session after the app process was killed (owner
     * correction: force-stop must not lose the session). The personal
     * snapshot is NOT re-captured — it was saved before the original join.
     */
    fun tryRestoreSession(): Boolean {
        if (activeSessionId.isNotBlank()) return false // already in a session
        val sid = prefs.getString(KEY_SESSION, null) ?: return false
        val pid = prefs.getString(KEY_PARTICIPANT, null) ?: return false
        val token = prefs.getString(KEY_TOKEN, null) ?: return false
        if (sid.isBlank() || pid.isBlank() || token.isBlank()) return false
        activeSessionId = sid
        myParticipantId = pid
        myToken = token
        // Force-stop: щит чекпоинтов должен остаться до выхода из Jam.
        engine.jamSuppressesCheckpoints = true
        startTransport()
        // SNAPSHOT confirms the session is still alive; if the server says
        // AUTH_FAILED, handleAuthFailed clears the persisted state.
        return true
    }

    private fun clearJamSessionState() {
        activeSessionId = ""
        myToken = ""
        session.reset()
        clearPersistedSession()
    }

    // ---- REST ----

    suspend fun createSession(name: String, avatarUrl: String, permissions: JamPermissions): Result<Unit> =
        runCatching {
            val account = Auth.account(context) ?: error("Not logged in")
            val response = withContext(Dispatchers.IO) {
                Net.postJson(
                    "$serverUrl/v1/sessions",
                    JSONObject()
                        .put("accountId", account.id)
                        .put("name", name.ifBlank { account.name })
                        .put("avatarUrl", avatarUrl.ifBlank { account.imageUrl })
                        .put("deviceId", com.project.puk.core.Identity.deviceId(context))
                        .put("permissions", JSONObject()
                            .put("guestsCanAdd", permissions.guestsCanAdd)
                            .put("guestsCanRemoveOwn", permissions.guestsCanRemoveOwn)
                            .put("guestsCanEditQueue", permissions.guestsCanEditQueue)
                            .put("guestsCanControlPlayback", permissions.guestsCanControlPlayback))
                        .toString(),
                    emptyMap(),
                    "jam-create",
                )
            } ?: error("Server unreachable")
            val body = JSONObject(response.body)
            if (response.code != 200) error(body.optString("message", "create failed"))
            startJoined(body)
            Unit
        }

    suspend fun joinByCode(code: String): Result<Unit> = runCatching {
        val account = Auth.account(context) ?: error("Not logged in")
        val response = withContext(Dispatchers.IO) {
            Net.postJson(
                "$serverUrl/v1/sessions/by-code/${code.trim().uppercase()}/join",
                JSONObject()
                    .put("accountId", account.id)
                    .put("name", account.name)
                    .put("avatarUrl", account.imageUrl)
                    .put("deviceId", com.project.puk.core.Identity.deviceId(context))
                    .toString(),
                emptyMap(),
                "jam-join",
            )
        } ?: error(Lang.t(Lang.Key.JAM_SERVER_UNAVAILABLE))
        val body = JSONObject(response.body)
        // Истёкшее/отозванное/несуществующее приглашение — понятный текст
        // вместо сырого кода (см. jamJoinErrorMessage).
        if (response.code != 200) error(jamJoinErrorMessage(body.optString("error"), body.optString("message")))
        startJoined(body)
        Unit
    }

    /** Wires WS + engine for a session the REST layer just joined. */
    private fun startJoined(body: JSONObject) {
        val sessionId = body.optString("sessionId")
        myParticipantId = body.optString("participantId")
        myToken = body.optString("token")
        activeSessionId = sessionId

        // Correction #2: capture the personal queue once, then shield it.
        engine.capturePersonalQueue()
        engine.jamSuppressesCheckpoints = true
        persistSession()

        startTransport()
        // The SNAPSHOT arrives over the socket and fills session state.
    }

    // ---- transport ----

    private fun startTransport() {
        controllerScope?.cancel()
        controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
            client = JamClient(scope, object : JamClient.Listener {
                override fun onEnvelope(envelope: JamEnvelope) {
                    // Envelope handling MUST run on the main thread: the
                    // applyingServerState guard only holds while the engine
                    // calls execute synchronously. From OkHttp's reader thread
                    // the guard resets before the deferred main-thread bodies
                    // of engine.pause()/seekTo() run, so every applied SEEK
                    // re-entered the transport delegate as a "user" command —
                    // a ~22 cmd/s echo loop until the server rate-limited it.
                    // launch(Main.immediate) keeps frame order (single reader
                    // thread → FIFO queue on main).
                    scope.launch { handleEnvelope(envelope) }
                }

                override fun onConnectionStateChanged(state: JamClient.ConnectionState) {
                    scope.launch {
                        session.ui.value = session.ui.value.copy(connection = state)
                        if (state == JamClient.ConnectionState.CONNECTED) startClockSync(scope)
                    }
                }

                override fun onAuthFailed(code: String) {
                    scope.launch {
                        Log.w(TAG, "auth failed: $code — session gone, clearing")
                        activeSessionId = ""
                        myToken = ""
                        // Stop every periodic job the session started: on a
                        // dead session they keep running forever and drive a
                        // reconnect/diagnostics storm (audit 2026-11-21).
                        scheduledStartJob?.cancel()
                        clockSyncJob?.cancel()
                        clockSyncJob = null
                        stalenessJob?.cancel()
                        stalenessJob = null
                        engineEventsJob?.cancel()
                        engineEventsJob = null
                        engineStateJob?.cancel()
                        engineStateJob = null
                        diagJob?.cancel()
                        diagJob = null
                        routeWatcher?.stop()
                        routeWatcher = null
                        releaseNudge()
                        JamDiagnostics.detach(context)
                        session.reset()
                        clearPersistedSession()
                        session.ui.value = session.ui.value.copy(connection = JamClient.ConnectionState.OFFLINE)
                        detachFromEngine()
                    }
                }

                override fun onServerClose(reason: String) {
                    scope.launch {
                        if (reason == "kicked" && isActive) {
                            Log.i(TAG, "server closed the socket: kicked")
                            leaveInternal()
                        }
                    }
                }
            }, serverUrl)
            client!!.configure(activeSessionId, myParticipantId, myToken)
            client!!.connect()
            observeEngine(scope)
            // Liveness watchdog: an idle TCP connection killed in the
            // background can look CONNECTED for minutes while nothing flows.
            // Our PING/PONG cadence guarantees frames every ~5 s, so a silent
            // 20 s window means a dead link — reconnect without the backoff.
            stalenessJob = scope.launch {
                while (isActive) {
                    delay(10_000)
                    val c = client ?: continue
                    if (c.isStale(STALE_AFTER_MS)) {
                        Log.w(TAG, "stale socket — reconnecting")
                        c.reconnectNow()
                    }
                }
            }
            // Sync-quality diagnostics (spec section 9): logcat-only, no UI.
            JamDiagnostics.attach(context)
            val audioManager = context.getSystemService(android.media.AudioManager::class.java)
            routeWatcher = audioManager?.let {
                AudioLatency.RouteWatcher(it) { refreshAudioLatency() }
            }?.also { it.start() }
            refreshAudioLatency()
            diagJob = scope.launch {
                while (isActive) {
                    delay(DIAG_SUMMARY_MS)
                    JamDiagnostics.logSummary(if (JamDiagnostics.appInForeground) "fg" else "bg")
                }
            }
        }
        // Register as the process-wide session: the engine transport delegate
        // routes user commands to the server, PlaybackService shapes commands.
        engine.jamTransportDelegate = this
        JamController.activeController = this
    }

    /**
     * Called on app foreground/resume: restores the link immediately instead
     * of waiting out the exponential backoff, and re-probes a socket that
     * silently died while the process was cached. Snapshot then re-anchors
     * track/position/play state (the 0:00-vs-2:18 owner bug).
     */
    fun onAppResume() {
        if (!isActive) return
        val c = client ?: return
        when {
            c.currentState() != JamClient.ConnectionState.CONNECTED -> c.reconnectNow()
            c.isStale(STALE_AFTER_MS) -> c.reconnectNow()
            else -> c.sendPing(SystemClock.elapsedRealtime())
        }
    }

    private fun detachFromEngine() {
        if (engine.jamTransportDelegate === this) engine.jamTransportDelegate = null
        if (JamController.activeController === this) JamController.activeController = null
    }

    private fun startClockSync(scope: CoroutineScope) {
        clockSyncJob?.cancel()
        clockSyncJob = scope.launch {
            // Burst-калибровка при подключении: серия быстрых проб собирает
            // robust-оценку offset за ~1 с, а не разогревает окно по одной
            // пробе за 5 с — старт первого PLAY уже точен (spec раздел 2).
            repeat(CLOCK_BURST_PROBES) {
                client?.sendPing(SystemClock.elapsedRealtime())
                delay(CLOCK_BURST_INTERVAL_MS)
            }
            while (isActive) {
                client?.sendPing(SystemClock.elapsedRealtime())
                delay(CLOCK_SYNC_MS)
            }
        }
    }

    private fun refreshAudioLatency() {
        val am = context.getSystemService(android.media.AudioManager::class.java) ?: return
        val route = AudioLatency.routeOf(am)
        audioLatencyMs = AudioLatency.totalMs(context)
        JamDiagnostics.noteLatency(audioLatencyMs, route.name)
        Log.i(TAG, "audio latency est ${audioLatencyMs}ms route=$route")
    }

    /** Тонкая коррекция выключается: скорость возвращается к пользовательской. */
    private fun releaseNudge() {
        if (!nudging) return
        nudging = false
        JamDiagnostics.noteResetSpeed()
        engine.applyDriftSpeed(engine.state.value.speed)
    }

    // ---- event handling ----

    /**
     * Marks engine mutations as server-driven: the transport delegate must
     * not echo them back as user commands (would loop CMD_PAUSE ⇄ PAUSE).
     */
    private inline fun applyingServerState(block: () -> Unit) {
        engine.jamApplyingServerCommand = true
        try {
            block()
        } finally {
            engine.jamApplyingServerCommand = false
        }
    }

    private fun handleEnvelope(envelope: JamEnvelope) {
        Log.i("Jam", "evt ${envelope.type}")
        val payload = envelope.payload
        when (envelope.type) {
            JamEnvelope.EV_SNAPSHOT -> {
                val state = session.applySnapshot(payload, myParticipantId)
                loadServerQueue(state, state.playback.index, state.playback.positionMs, autoplay = false)
                scheduleServerPlayback(state.playback)
                sendSyncReport(if (state.playback.state == "PLAYING") "READY" else "IDLE")
            }

            JamEnvelope.EV_QUEUE_UPDATED -> {
                val updated = session.applyQueueUpdated(payload) ?: return
                // Партия дошла до сервера: снять «Добавляется…» у источника
                // и подтвердить ожидающую порцию (старые серверы без
                // sourceRef подтверждает сам факт QUEUE_UPDATED).
                payload.optString("sourceRef").ifBlank { null }?.let { session.finishAdding(it) }
                pendingBatchResult?.complete(true)
                // Queue replace only when the version actually changed (correction #1);
                // the active item follows its entry.
                refreshLoadedQueue(updated)
            }

            JamEnvelope.EV_PLAYBACK_PREPARE -> {
                val playback = session.applyPlaybackUpdate(payload, "PREPARING", payload.optLong("positionMs", 0L), null) ?: return
                pendingPrepareReported = false
                prepareStartedAtElapsedMs = SystemClock.elapsedRealtime()
                prepareUri = playback.uri
                // Track change (correction #1): load the new item paused; a
                // matching already-loaded queue skips the full reload.
                val staggerMs = prepareStaggerMs(playback)
                if (staggerMs > 0) {
                    // Детерминированный стаггер (правки 1–2): участники одной
                    // сессии за общим NAT бьют в license-endpoint Spotify
                    // одновременно и ловят 429 целыми сессиями. 0–600 мс по
                    // хэшу участника растягивают запросы; барьер READY (4 с)
                    // и стартовый lead поглощают задержку.
                    val version = playback.stateVersion
                    controllerScope?.launch {
                        delay(staggerMs)
                        val live = session.ui.value.session ?: return@launch
                        if (live.playback.stateVersion < version) return@launch // superseded
                        if (engineMatchesPlayback(live.playback)) return@launch // трек уже в движке
                        // PLAY мог прийти внутри окна стаггера (барьер закрылся
                        // раньше) — трек всё равно нужно загрузить.
                        if (live.playback.state != "PREPARING" && live.playback.state != "PLAYING") return@launch
                        loadServerQueue(live, playback.index, playback.positionMs, autoplay = false)
                    }
                } else {
                    loadServerQueue(session.ui.value.session ?: return, playback.index, playback.positionMs, autoplay = false)
                }
            }

            JamEnvelope.EV_PLAYBACK_PLAY -> {
                val playback = session.applyPlaybackUpdate(
                    payload, "PLAYING",
                    payload.optLong("positionMs", 0L),
                    payload.optLong("startAtServerMs", 0L),
                ) ?: return
                scheduleServerPlayback(playback)
            }

            JamEnvelope.EV_PLAYBACK_PAUSE -> {
                val playback = session.applyPlaybackUpdate(payload, "PAUSED", payload.optLong("positionMs", 0L), null) ?: return
                scheduledStartJob?.cancel()
                applyingServerState {
                    if (engine.state.value.playing) engine.pause()
                    engine.seekTo(playback.positionMs.coerceAtLeast(0))
                }
            }

            JamEnvelope.EV_PLAYBACK_SEEK -> {
                val playback = session.applyPlaybackUpdate(payload, session.ui.value.session?.playback?.state ?: "PAUSED", payload.optLong("positionMs", 0L), null) ?: return
                applyingServerState { engine.seekTo(playback.positionMs.coerceAtLeast(0)) }
            }

            JamEnvelope.EV_PERMISSIONS_UPDATED -> session.applyPermissions(payload)
            JamEnvelope.EV_MODE_UPDATED -> handleModeChanged(payload.optString("playMode", "SYNC"), payload.optString("soloParticipantId").ifBlank { null })

            JamEnvelope.EV_PARTICIPANT_JOINED -> session.applyParticipant(payload, envelope.type)
            JamEnvelope.EV_PARTICIPANT_LEFT -> session.applyParticipant(payload, envelope.type)
            JamEnvelope.EV_KICKED -> {
                // Сервер рассылает KICKED всем участникам: покидать сессию
                // должен только исключённый. Ведущий, кикнувшего гостя,
                // оставался без сессии (владелец: «Джем закрывается»).
                val kickedId = payload.optString("participantId")
                if (kickedId == myParticipantId) leaveInternal()
            }
            JamEnvelope.EV_PARTICIPANT_UPDATED -> {
                payload.optJSONObject("participant")?.let(JamParticipant::parse)?.let { session.replaceParticipant(it) }
            }

            JamEnvelope.EV_HOST_CHANGED -> session.applyHostChanged(payload.optString("hostId"))

            JamEnvelope.EV_SESSION_ENDED -> {
                val reason = payload.optString("reason")
                Log.i(TAG, "session ended ($reason)")
                leaveInternal()
            }

            JamEnvelope.EV_PONG -> {
                val clientTime = payload.optLong("t", 0L)
                val serverTime = payload.optLong("serverTimeMs", 0L)
                val now = SystemClock.elapsedRealtime()
                val nowWall = System.currentTimeMillis()
                if (clientTime > 0 && serverTime > 0) {
                    val sample = JamSync.sample(clientTime, now, serverTime, nowWall)
                    session.applyServerTime(sample)
                    // В диагностику — применённая robust-оценка, а не сырой
                    // последний семпл (он скачет на ±RTT/2 и вводит в
                    // заблуждение при чтении логов).
                    session.offset?.let {
                        JamDiagnostics.noteClockSample(it.rttMs, it.offsetMs)
                    }
                    sendSyncReport(session.ui.value.session?.playback?.state ?: "IDLE")
                }
            }

            JamEnvelope.EV_ERROR -> {
                val code = payload.optString("code")
                Log.w(TAG, "server error: $code ${payload.optString("eventId")}")
                // The transport delegate consumes user play/pause and waits for
                // the server broadcast. If the server has lost its playback
                // state (stale session, divergence after TRACK_ENDED) a pause
                // would be silently dropped and the audio unpauseable — honor
                // it locally instead.
                if (code == "not_playing" && engine.state.value.playing) {
                    applyingServerState { engine.pause() }
                }
                // Подтверждение ожидающей порции ADD_PLAYLIST: дубликат —
                // партия уже применена (успех), отказ — порция провалилась.
                if (code == "duplicate") pendingBatchResult?.complete(true)
                else if (code in ADD_FAILURE_CODES) pendingBatchResult?.complete(false)
                // Ошибки добавления/действий не маскируются под успех:
                // «Добавляется…» снимается, пользователь видит причину.
                val sourceRef = payload.optString("sourceRef").ifBlank { null }
                when (code) {
                    "queue_full" -> session.failAdding(
                        sourceRef.orEmpty(),
                        Lang.t(Lang.Key.JAM_QUEUE_FULL_STOPPED),
                    )
                    "queue_partial" -> {
                        if (sourceRef != null) session.finishAdding(sourceRef)
                        val requested = payload.optLong("requested", 0L)
                        val added = payload.optLong("added", 0L)
                        session.ui.value = session.ui.value.copy(
                            notice = Lang.t(Lang.Key.JAM_QUEUE_ALMOST_FULL, added, requested),
                        )
                    }
                    "permission_denied" -> session.ui.value =
                        session.ui.value.copy(notice = Lang.t(Lang.Key.JAM_ACTION_REJECTED))
                    "rate_limited" -> session.ui.value =
                        session.ui.value.copy(notice = Lang.t(Lang.Key.JAM_TOO_MANY_COMMANDS))
                    "unsupported_content" -> session.ui.value =
                        session.ui.value.copy(notice = Lang.t(Lang.Key.JAM_CONTENT_UNSUPPORTED))
                }
            }
        }
    }

    /**
     * Локально играет: SYNC — все; SOLO — только ведущий (корректировка №8
     * спеки не требует — режим по запросу владельца).
     */
    fun playsLocallyNow(): Boolean = playsLocally()

    /**
     * Авторитетная серверная позиция, интерполированная по настенным часам
     * (якорь — момент старта, см. JamSession.expectedPosition): для зеркал в
     * SOLO и для выравнивания при опоздавшем старте. Null, когда нечего
     * показывать.
     */
    fun serverPositionNow(): Long? {
        val st = session.ui.value.session ?: return null
        if (st.playback.itemId == null) return null
        return session.expectedPosition()
    }

    private fun playsLocally(): Boolean {
        val state = session.ui.value.session ?: return true
        if (state.playMode == "SYNC") return true
        // SOLO: играет выбранный участник (fallback — ведущий).
        val soloId = state.soloParticipantId ?: state.host?.id
        return state.me()?.id == soloId
    }

    private fun handleModeChanged(mode: String, soloParticipantId: String?) {
        val state = session.applyMode(mode, soloParticipantId) ?: return
        val wasPlaying = engine.state.value.playing
        if (playsLocally()) {
            // Новый солирующий или возврат в SYNC: подключиться к позиции сервера.
            val playback = state.playback
            loadServerQueue(state, playback.index, playback.positionMs, autoplay = false)
            if (playback.state == "PLAYING") scheduleServerPlayback(playback)
        } else if (wasPlaying) {
            scheduledStartJob?.cancel()
            applyingServerState { engine.pause() }
        }
    }

    // ---- local playback mapping ----

    /** Loads the server queue into the engine when it differs (correction #1). */
    private fun loadServerQueue(state: JamSessionState, index: Int, positionMs: Long, autoplay: Boolean) {
        enrichMissingArtwork(state)
        Log.i("Jam", "loadServerQueue idx=$index solo=${!playsLocally()} loaded=${session.ui.value.queueLoaded}")
        if (!playsLocally()) return // SOLO: играет только устройство ведущего
        val items = state.queue.map { entry ->
            PukPlayer.QueueItem(
                uri = entry.uri,
                title = entry.title,
                artist = entry.artist,
                albumUri = "",
                imageUrl = entry.imageUrl,
                durationMs = entry.durationMs,
            )
        }
        if (items.isEmpty()) return
        val safeIndex = index.coerceIn(0, items.lastIndex)
        val current = engine.state.value
        // Фактически загруженный источник обязан совпадать с целевым треком:
        // после отменённого prepare состояние движка могло указывать на новый
        // трек при старом источнике — без этой проверки «уже загружен»
        // перезапускал старый трек с новым UI (живой баг).
        val alreadyLoaded = current.queueContext == "jam:${state.sessionId}" &&
            current.queue.map { it.uri } == items.map { it.uri } &&
            current.index == safeIndex &&
            engine.loadedSourceUri() == items[safeIndex].uri
        if (alreadyLoaded) {
            // Identical queue and item: never rebuild the source (a reconnect
            // must not restart the track — owner fix). Only align position.
            applyingServerState {
                engine.seekTo(positionMs.coerceAtLeast(0))
                if (autoplay && !engine.state.value.playing) engine.resume()
            }
            session.ui.value = session.ui.value.copy(queueLoaded = true)
            return
        }
        scheduledStartJob?.cancel()
        engine.playQueue(
            items, safeIndex, autoplay, positionMs.coerceAtLeast(0),
            queueContext = "jam:${state.sessionId}",
        )
        session.ui.value = session.ui.value.copy(queueLoaded = true)
    }

    private fun refreshLoadedQueue(state: JamSessionState) {
        enrichMissingArtwork(state)
        val current = engine.state.value
        if (current.queueContext != "jam:${state.sessionId}") return
        val items = state.queue.map { entry ->
            PukPlayer.QueueItem(
                uri = entry.uri, title = entry.title, artist = entry.artist,
                albumUri = "", imageUrl = entry.imageUrl, durationMs = entry.durationMs,
            )
        }
        if (items.map { it.uri } == current.queue.map { it.uri }) return
        // The active item follows its entry by URI OCCURRENCE: one track may
        // appear several times, first-match by URI would bind to the wrong
        // duplicate. Falls back to the server's authoritative index.
        val activeUri = current.queue.getOrNull(current.index)?.uri
        val occurrence = if (activeUri != null) current.queue.subList(0, current.index).count { it.uri == activeUri } else -1
        val newIndex = if (activeUri != null && occurrence >= 0) {
            var seen = -1
            val found = items.indexOfFirst { it.uri == activeUri && ++seen == occurrence }
            if (found >= 0) found
            else if (state.playback.itemId != null) current.index.coerceAtMost(items.lastIndex)
            else 0
        } else if (state.playback.itemId == null) 0
        else current.index.coerceAtMost(items.lastIndex)
        // Живая подмена очереди: ExoPlayer держит только текущий трек, поэтому
        // обычной замены state достаточно — источник не перезагружается и
        // воспроизведение не прерывается (владелец: «трек переста играть»).
        engine.applyJamQueue(items, newIndex)
    }

    /**
     * Guest apps may push entries without artwork: resolve the cover via
     * oEmbed (same endpoint Connect uses for remote tracks) and patch both
     * the Jam UI and the local queue. Bounded, deduplicated per session.
     */
    private fun enrichMissingArtwork(state: JamSessionState) {
        val missing = state.queue.filter { it.imageUrl.isBlank() && !artworkRequested.contains(it.uri) }
        if (missing.isEmpty()) return
        missing.forEach { artworkRequested.add(it.uri) }
        val sid = state.sessionId
        controllerScope?.launch {
            for (entry in missing) {
                val id = entry.uri.removePrefix("spotify:track:")
                val response = withContext(Dispatchers.IO) {
                    runCatching {
                        Net.get("https://open.spotify.com/oembed?url=https%3A%2F%2Fopen.spotify.com%2Ftrack%2F$id", tag = "jam-art")
                    }.getOrNull()
                } ?: continue
                val image = response?.takeIf { it.ok }?.json()?.optString("thumbnail_url").orEmpty()
                if (image.isBlank()) continue
                session.ui.value.session?.let { st ->
                    val updated = st.copy(queue = st.queue.map {
                        if (it.uri == entry.uri) it.copy(imageUrl = image) else it
                    })
                    session.ui.value = session.ui.value.copy(session = updated)
                }
                engine.updateQueueItemArtwork(entry.uri, image)
            }
        }
    }

    /**
     * Детерминированный стаггер подготовки (правки 1–2, license 429):
     * возвращает 0, когда лицензия уже есть (тот же трек / reconnect /
     * повторный PREPARE) или устройство не играет; иначе 0–600 мс по хэшу
     * участника.
     */
    private fun prepareStaggerMs(playback: JamPlayback): Long {
        if (!playsLocally()) return 0
        val engineUri = engine.state.value.queue.getOrNull(engine.state.value.index)?.uri
        if (engineUri == playback.uri) return 0
        if (engine.hasResolvedSource(playback.uri)) return 0
        val hash = myParticipantId.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
        return hash % PREPARE_STAGGER_MAX_MS
    }

    /**
     * Schedules the authoritative start moment (correction #4): prepare
     * locally, then start playback when the server clock reaches startAt.
     * A start moment already in the past means a mid-song join or reconnect:
     * jump straight to the effective position and resume — never restart the
     * track from 0 (the 0:00-vs-2:18 owner bug).
     */
    private fun scheduleServerPlayback(playback: JamPlayback) {
        scheduledStartJob?.cancel()
        if (!playsLocally()) return // SOLO: только зеркало состояния
        if (playback.state != "PLAYING") return
        if (playback.startedAtServerMs <= 0) {
            applyingServerState { engine.resume() }
            return
        }
        val serverNow = JamSync.serverTimeNow(session.offset?.offsetMs, System.currentTimeMillis())
        val rawDelay = playback.startedAtServerMs - serverNow
        // Якорь старта — монотонные часы (spec раздел 2): ожидание, запуск и
        // дрейф-монитор живут в одном времени, настенные прыжки не сдвигают
        // старт и не ломают ожидаемую позицию.
        val startAtElapsed = SystemClock.elapsedRealtime() + rawDelay.coerceAtLeast(0)
        if (rawDelay <= 0) {
            beginPlaybackAtExpected(playback, startAtElapsed)
            return
        }
        // Точный старт: задержка ровно до startAtServerMs (завышение на
        // MIN_START_LEAD добавляло ~0,4 c рассинхрона).
        val delayMs = rawDelay.coerceAtMost(JamSync.MAX_START_LEAD_MS)
        scheduledStartJob = controllerScope?.launch {
            // Загрузка решается фактом «движок держит серверный трек», а не
            // липким флагом queueLoaded (оставался true от предыдущего трека
            // и оставлял играть старый источник с новым UI — живой баг).
            if (!engineMatchesPlayback(playback)) {
                loadServerQueue(session.ui.value.session ?: return@launch, playback.index, playback.positionMs, autoplay = true)
            }
            if (delayMs > 0) delay(delayMs)
            // Готовность (лицензия/буфер) должна лечь в запас lead'а ДО
            // стартового момента; иначе — late-join на актуальной позиции.
            val prepareDeadline = startAtElapsed - 300
            while (isActive && !engineReady() && SystemClock.elapsedRealtime() < prepareDeadline) delay(25)
            if (!engineReady()) {
                beginPlaybackAtExpected(playback, startAtElapsed)
                return@launch
            }
            // Звук выходит из динамика на ~latency позже play(): запускаем
            // заранее ровно на эту величину (spec раздел 4).
            waitPreciselyUntil(startAtElapsed - audioLatencyMs)
            beginPlaybackAtExpected(playback, startAtElapsed)
        }
    }

    /** Точное ожидание на монотонных часах: хвост < 30 мс — спин по 2 мс. */
    private suspend fun waitPreciselyUntil(targetElapsedMs: Long) {
        while (true) {
            val remain = targetElapsedMs - SystemClock.elapsedRealtime()
            if (remain <= 0) return
            delay(if (remain > 30) remain - 20 else 2)
        }
    }

    /**
     * Старт/присоединение на серверной позиции: seek только при заметном
     * гэпе (без лишних прерываний), затем resume. Клиент, чей источник пришёл
     * после барьера, стартует с актуальной позиции, а не с 0 с опозданием.
     */
    private fun beginPlaybackAtExpected(playback: JamPlayback, startAtElapsedMs: Long) {
        scheduledStartJob?.cancel()
        val scheduledFor = startAtElapsedMs - audioLatencyMs
        scheduledStartJob = controllerScope?.launch {
            // Страховка: если серверный трек так и не попал в движок (все пути
            // загрузки опоздали), загрузить здесь — seekTo/resume по чужому
            // источнику перезапускал старый трек с новым UI (живой баг).
            if (!engineMatchesPlayback(playback)) {
                loadServerQueue(session.ui.value.session ?: return@launch, playback.index, playback.positionMs, autoplay = false)
            }
            val deadline = SystemClock.elapsedRealtime() + 3_000
            while (isActive && !engineReady() && SystemClock.elapsedRealtime() < deadline) delay(25)
            applyingServerState {
                if (engineReady()) {
                    // Цель движка = серверная позиция + локальная аудио-задержка:
                    // звук тогда выходит ровно на серверной позиции (spec 4).
                    val expected = session.expectedPosition() + audioLatencyMs
                    val actual = engine.livePositionMs() ?: engine.state.value.positionMs
                    if (kotlin.math.abs(expected - actual) > 120) engine.seekTo(expected.coerceAtLeast(0))
                }
                // Не готов — всё равно resume: playWhenReady стартует, как
                // только буфер наполнится; дрейф-монитор вытянет позицию.
                engine.resume()
            }
            JamDiagnostics.notePlayStart(SystemClock.elapsedRealtime() - scheduledFor)
        }
    }

    /** Reports readiness/RTT for the readiness barrier (correction #4). */
    private fun sendSyncReport(forState: String) {
        if (!isActive) return
        val playback = session.ui.value.session?.playback ?: return
        val readyState = when {
            forState == "PREPARING" && engineReady() && engineOnServerTrack(playback) -> "READY"
            engine.state.value.error != null -> "ERROR"
            else -> forState
        }
        val rtt = session.offset?.rttMs ?: 0L
        client?.send(JamEnvelope.CMD_SYNC_REPORT, payload = JSONObject()
            .put("readyState", readyState)
            .put("rttMs", rtt)
            .put("positionMs", engine.state.value.positionMs))
    }

    private fun engineReady(): Boolean =
        engine.state.value.licenseReady && !engine.state.value.buffering

    /**
     * Движок реально держит серверный текущий трек: без этой проверки
     * READY-отчёт и замер дрейфа срабатывали на СТАРОМ источнике в окне
     * смены трека (ложный READY закрывал барьер мгновенно; дрейф-монитор
     * сравнивал ожидание нового трека с позицией старого — «гэп 166 с»).
     */
    private fun engineOnServerTrack(playback: JamPlayback): Boolean =
        engine.current()?.uri == playback.uri && playback.uri.isNotBlank()

    /** Полное соответствие: uri, index и ФАКТИЧЕСКИ загруженный источник. */
    private fun engineMatchesPlayback(playback: JamPlayback): Boolean =
        engineOnServerTrack(playback) &&
            engine.state.value.index == playback.index &&
            engine.loadedSourceUri() == playback.uri

    // ---- engine observation ----

    private fun observeEngine(scope: CoroutineScope) {
        engineEventsJob?.cancel()
        engineEventsJob = scope.launch {
            // Track end on the host: the server decides what plays next
            // (correction #3) — report instead of advancing locally.
            engine.events.collect { event ->
                // Contract: only the host reports the ended track; guests
                // reporting it relied on server-side dedup and raced with the
                // host's own report (audit 2026-11-21).
                if (event == "ended" && isActive && session.ui.value.session?.me()?.isHost == true) {
                    client?.send(JamEnvelope.CMD_TRACK_ENDED, payload = JSONObject())
                }
            }
        }
        engineStateJob?.cancel()
        engineStateJob = scope.launch {
            var lastReportedVersion = -1L
            while (isActive) {
                val sessionState = session.ui.value.session
                if (sessionState == null) {
                    delay(500)
                    continue
                }
                val playback = sessionState.playback
                // READY reporting for the prepare barrier: only when the
                // engine actually holds the PREPARING track (см. engineOnServerTrack).
                if (playback.state == "PREPARING" && !pendingPrepareReported && engineReady() && engineOnServerTrack(playback)) {
                    pendingPrepareReported = true
                    if (prepareStartedAtElapsedMs > 0) {
                        JamDiagnostics.notePrepareDuration(
                            prepareUri,
                            SystemClock.elapsedRealtime() - prepareStartedAtElapsedMs,
                        )
                        prepareStartedAtElapsedMs = 0
                    }
                    client?.send(JamEnvelope.CMD_SYNC_REPORT, payload = JSONObject()
                        .put("readyState", "READY")
                        .put("rttMs", session.offset?.rttMs ?: 0L))
                }
                // Drift monitor: local silent correction only (correction #4).
                // Гейт на engineReady: буферизация сама держит позицию — без
                // гейта это выглядело бы как рассинхрон и дёргало seek.
                if (playback.state == "PLAYING" && engine.state.value.playing && playsLocally() &&
                    engineReady() && engineOnServerTrack(playback)
                ) {
                    if (session.checkWallJump()) {
                        // Настенные часы скорректировались (NTP/NITZ): offset
                        // мёртв — пересобираем burst'ом. Якоря позиций стоят
                        // на elapsed и от прыжка не страдают.
                        Log.w(TAG, "wall clock jump — clock burst restarted")
                        driftStreak = 0
                        releaseNudge()
                        startClockSync(scope)
                    }
                    // Цель движка включает локальную аудио-задержку: звук у
                    // всех устройств тогда совпадает по факту (spec 4).
                    val expected = session.expectedPosition() + audioLatencyMs
                    // Живая позиция движка (аудиочасы): state.positionMs
                    // обновляется циклом раз в 500 мс и давал системную пилу
                    // 0–500 мс → бесконечные seek'и (замер на живой сессии).
                    val actual = engine.livePositionMs()
                    if (actual == null) {
                        // Источник подменяется/не загружен — замер в этот тик
                        // недоступен, коррекцию не трогаем.
                        driftStreak = 0
                        releaseNudge()
                    } else {
                        val signed = actual - expected
                        val gap = kotlin.math.abs(signed)
                        JamDiagnostics.noteGap(gap)
                        driftStreak = if (gap >= JamSync.NUDGE_IGNORE_MS) driftStreak + 1 else 0
                        val seekOnCooldown =
                            SystemClock.elapsedRealtime() - lastSeekAtElapsedMs < JamSync.SEEK_COOLDOWN_MS
                        when (val plan = JamSync.driftPlan(signed, driftStreak.toLong(), nudging)) {
                            JamSync.DriftPlan.HOLD -> Unit
                            JamSync.DriftPlan.RESET_SPEED -> releaseNudge()
                            JamSync.DriftPlan.NUDGE_DOWN, JamSync.DriftPlan.NUDGE_UP -> {
                                // 0,5–2 % скорости, высота тона сохраняется
                                // (ExoPlayer time-stretch); локально, без SEEK-
                                // команд серверу — эхо-петля исключена.
                                val factor = JamSync.nudgeFactor(gap)
                                nudging = true
                                val total = engine.state.value.speed *
                                    (if (plan == JamSync.DriftPlan.NUDGE_DOWN) 1.0 - factor else 1.0 + factor)
                                engine.applyDriftSpeed(total.toFloat())
                                JamDiagnostics.noteNudge(total.toDouble())
                            }
                            JamSync.DriftPlan.SEEK -> if (!seekOnCooldown) {
                                applyingServerState { engine.seekTo(expected.coerceAtLeast(0)) }
                                lastSeekAtElapsedMs = SystemClock.elapsedRealtime()
                                driftStreak = 0
                                releaseNudge()
                                JamDiagnostics.noteSeek(gap)
                            }
                        }
                    }
                } else {
                    driftStreak = 0
                    releaseNudge()
                }
                if (playback.stateVersion != lastReportedVersion) {
                    lastReportedVersion = playback.stateVersion
                }
                delay(DRIFT_CHECK_MS)
            }
        }
    }

    // ---- user actions (permission-gated client-side, enforced server-side) ----

    fun addTrack(uri: String, title: String, artist: String, imageUrl: String, durationMs: Long): Boolean {
        Log.i("Jam", "addTrack uri=$uri supported=${isSupportedTrackUri(uri)} active=$isActive")
        if (!isSupportedTrackUri(uri)) return false
        if (!isActive) return false
        // sourceRef = сам трек: одиночные добавления тоже видны в статусах.
        client?.send(JamEnvelope.CMD_ADD_TRACK, payload = JSONObject()
            .put("uri", uri)
            .put("title", title)
            .put("artist", artist)
            .put("imageUrl", imageUrl)
            .put("durationMs", durationMs)
            .put("sourceRef", uri))
        return true
    }

    fun removeTrack(itemId: String) {
        if (!isActive) return
        client?.send(JamEnvelope.CMD_REMOVE_TRACK, payload = JSONObject().put("itemId", itemId))
    }

    /** Удалить из очереди все треки одной партии (плейлиста) — одна команда, один broadcast. */
    fun removeSource(sourceRef: String) {
        if (!isActive || sourceRef.isBlank()) return
        client?.send(JamEnvelope.CMD_REMOVE_SOURCE, payload = JSONObject().put("sourceRef", sourceRef))
    }

    fun moveTrack(itemId: String, toIndex: Int) {
        if (!isActive) return
        client?.send(JamEnvelope.CMD_MOVE_TRACK, payload = JSONObject()
            .put("itemId", itemId).put("toIndex", toIndex))
    }

    fun play(itemId: String? = null) {
        if (!isActive) return
        val payload = JSONObject()
        itemId?.let { payload.put("itemId", it) }
        client?.send(JamEnvelope.CMD_PLAY, payload = payload)
    }

    /**
     * Плейлист — порциями по [ADD_PLAYLIST_BATCH] треков: кадр 1000 треков
     * (~260 КБ) превышает лимит WS-кадра 256 КБ, поэтому 1000 уезжает тремя
     * командами по ~100 КБ. Каждая порция ждёт подтверждения сервера
     * (QUEUE_UPDATED или ERROR) перед следующей; при отказе/тайм-ауте
     * отправка останавливается и «Добавляется…» снимается — частичный сбой
     * не маскируется под успех (владелец: вечное «Добавляется» при полной
     * очереди). Дедуп eventId сервера защищает от повторов.
     */
    fun addPlaylist(entries: List<PukPlayer.QueueItem>, sourceRef: String) {
        if (!isActive) return
        val valid = entries.filter { com.project.puk.jam.isSupportedTrackUri(it.uri) }
        if (valid.isEmpty()) return
        session.registerPartition(sourceRef, valid.size)
        controllerScope?.launch {
            var offset = 0
            while (offset < valid.size && isActive) {
                val slice = valid.subList(offset, minOf(offset + ADD_PLAYLIST_BATCH, valid.size))
                val array = org.json.JSONArray()
                for (item in slice) {
                    array.put(JSONObject()
                        .put("uri", item.uri)
                        .put("title", item.title)
                        .put("artist", item.artist)
                        .put("imageUrl", item.imageUrl)
                        .put("durationMs", item.durationMs))
                }
                val confirm = CompletableDeferred<Boolean>()
                pendingBatchResult = confirm
                val sent = client?.send(
                    JamEnvelope.CMD_ADD_PLAYLIST,
                    payload = JSONObject().put("entries", array).put("sourceRef", sourceRef),
                )
                if (sent == null) {
                    pendingBatchResult = null
                    session.failAdding(sourceRef, Lang.t(Lang.Key.JAM_CONNECTION_LOST_ADD))
                    return@launch
                }
                val outcome = withTimeoutOrNull(BATCH_CONFIRM_TIMEOUT_MS) { confirm.await() }
                pendingBatchResult = null
                when (outcome) {
                    true -> offset += slice.size
                    // ERROR уже показал причину тостом — только снять статус.
                    false -> { session.finishAdding(sourceRef); return@launch }
                    null -> {
                        session.finishAdding(sourceRef)
                        session.ui.value = session.ui.value.copy(
                            notice = Lang.t(Lang.Key.JAM_ADD_NOT_CONFIRMED),
                        )
                        return@launch
                    }
                }
            }
            // На всякий случай для старых серверов без sourceRef в кадрах.
            session.finishAdding(sourceRef)
        }
    }

    fun shuffle(enabled: Boolean) {
        if (!isActive) return
        client?.send(JamEnvelope.CMD_SHUFFLE, payload = JSONObject().put("enabled", enabled))
    }

    fun updateMode(mode: String, soloParticipantId: String? = null) {
        if (!isActive || session.ui.value.session?.me()?.isHost != true) return
        val payload = JSONObject().put("playMode", mode)
        soloParticipantId?.let { payload.put("soloParticipantId", it) }
        client?.send(JamEnvelope.CMD_UPDATE_MODE, payload = payload)
    }

    /** Пауза доступна каждому участнику Jam (запрос владельца). */
    fun pause() {
        if (!isActive) return
        client?.send(JamEnvelope.CMD_PAUSE, payload = JSONObject())
    }

    fun seek(positionMs: Long) {
        if (!isActive) return
        client?.send(JamEnvelope.CMD_SEEK, payload = JSONObject().put("positionMs", positionMs))
    }

    /** NEXT/PREV — у всех участников (равные права); сервер решает элемент. */
    fun next() {
        if (!isActive) return
        client?.send(JamEnvelope.CMD_NEXT, payload = JSONObject())
    }

    fun previous() {
        if (!isActive) return
        client?.send(JamEnvelope.CMD_PREV, payload = JSONObject())
    }

    /** MediaSession command shaping: skip stays available to everyone. */
    fun canSkipFromSession(): Boolean = isActive

    // ---- PukPlayer.TransportDelegate ----
    // System media buttons / notification / player screen / headset route
    // through the server while a session is active. Consumed even when the
    // permission gate drops the command — guests must never act locally
    // on the shared queue.

    override fun onPlay(): Boolean {
        if (!isActive) return false
        play()
        return true
    }

    override fun onPause(): Boolean {
        if (!isActive) return false
        pause()
        return true
    }

    override fun onNext(): Boolean {
        if (!isActive) return false
        next()
        return true
    }

    override fun onPrevious(): Boolean {
        if (!isActive) return false
        previous()
        return true
    }

    override fun onSeek(positionMs: Long): Boolean {
        if (!isActive) return false
        seek(positionMs)
        return true
    }

    override fun onPlayUri(uri: String): Boolean {
        if (!isActive) return false
        session.ui.value.session?.queue?.firstOrNull { it.uri == uri }?.let { play(it.itemId) }
        return true
    }

    fun updatePermissions(permissions: JamPermissions) {
        if (!isActive || session.ui.value.session?.me()?.isHost != true) return
        client?.send(JamEnvelope.CMD_UPDATE_PERMISSIONS, payload = JSONObject()
            .put("guestsCanAdd", permissions.guestsCanAdd)
            .put("guestsCanRemoveOwn", permissions.guestsCanRemoveOwn)
            .put("guestsCanEditQueue", permissions.guestsCanEditQueue)
            .put("guestsCanControlPlayback", permissions.guestsCanControlPlayback))
    }

    fun transferHost(targetId: String) {
        if (!isActive) return
        client?.send(JamEnvelope.CMD_TRANSFER_HOST, payload = JSONObject().put("participantId", targetId))
    }

    fun kick(participantId: String) {
        if (!isActive) return
        client?.send(JamEnvelope.CMD_KICK, payload = JSONObject().put("participantId", participantId))
    }

    fun revokeInvite() {
        if (!isActive) return
        client?.send(JamEnvelope.CMD_REVOKE_INVITE, payload = JSONObject())
    }

    fun endSession() {
        if (!isActive) return
        client?.send(JamEnvelope.CMD_END_SESSION, payload = JSONObject())
        leaveInternal()
    }

    fun leave() {
        if (!isActive) return
        client?.send(JamEnvelope.CMD_LEAVE, payload = JSONObject())
        leaveInternal()
    }

    /** UI consumed the one-shot server notice (queue_partial и др.). */
    fun clearNotice() {
        session.ui.value = session.ui.value.copy(notice = null)
    }

    private fun leaveInternal() {
        if (!isActive) return
        scheduledStartJob?.cancel()
        diagJob?.cancel()
        diagJob = null
        routeWatcher?.stop()
        routeWatcher = null
        releaseNudge()
        JamDiagnostics.detach(context)
        client?.disconnect()
        controllerScope?.cancel()
        controllerScope = null
        detachFromEngine()
        engine.jamSuppressesCheckpoints = false
        // Spec section 15: leaving must never cut the audio abruptly. When
        // the Jam track is still playing, keep it and offer the restore; a
        // silent player restores the personal queue right away.
        val current = engine.state.value
        val stillPlayingJam = current.playing &&
            current.queueContext?.startsWith("jam:") == true &&
            current.index in current.queue.indices
        if (stillPlayingJam) {
            // Keep shielding the personal snapshot: periodic checkpoints must
            // not write Jam content into it (owner correction #2).
            personalRestorePending = true
        } else {
            engine.restorePersonalQueue()
        }
        activeSessionId = ""
        myToken = ""
        artworkRequested.clear()
        session.reset()
    }

    /** User-confirmed restore after leaving (spec section 15). */
    fun restorePersonalQueueNow() {
        personalRestorePending = false
        engine.restorePersonalQueue(force = true)
    }

    /** The restore offer survives process death via the engine flag. */
    fun restorePendingNow(): Boolean = personalRestorePending || engine.jamSuppressesCheckpoints

    fun shutdown() {
        client?.shutdown()
        leaveInternal()
    }
}
