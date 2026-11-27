package com.project.puk.connect

import android.content.Context
import android.os.SystemClock
import com.project.puk.api.Spotify
import com.project.puk.core.Auth
import com.project.puk.core.ClientTokenManager
import com.project.puk.core.Identity
import com.project.puk.core.Log
import com.project.puk.core.Net
import com.project.puk.core.TokenMinter
import com.project.puk.player.PukPlayer
import com.project.puk.player.PlaybackService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Spotify Connect integration. The app registers itself as a real connect
 * player (the way librespot and the web player do), keeps the registration
 * alive while playing, publishes its player state and applies commands that
 * arrive from other clients (desktop, phone app, web) over the dealer socket.
 */
object Connect {

    const val DEVICE_NAME = "SpotiPuk"

    private const val URI_CONNECTIONS = "hm://pusher/v1/connections/"
    private const val URI_COMMAND = "hm://track-playback/v1/command"
    private const val URI_CLUSTER = "hm://connect-state/v1/cluster"

    private const val PING_MS = 25_000L
    private const val STATE_POLL_MS = 1_000L
    private const val STATE_REFRESH_MS = 55_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var dealerHost = ""
    @Volatile private var spclientHost = ""
    @Volatile private var connectionId = ""
    @Volatile private var lastFrameAt = 0L
    @Volatile private var registered = false
    @Volatile private var playbackTransferredAway = false
    @Volatile private var sessionId = UUID.randomUUID().toString()
    @Volatile private var myDeviceId = ""

    /** Device list seen in the cluster (for "Listening on" style UI later). */
    val clusterActiveDevice = MutableStateFlow("")

    data class RemoteTrack(val title: String, val artist: String, val artwork: String, val device: String)

    data class OutputDevice(val id: String, val name: String)

    private val outputDevices = MutableStateFlow<List<OutputDevice>>(emptyList())
    val devices = outputDevices.asStateFlow()
    @Volatile var transferError: String? = null
        private set

    fun isLocalDevice(id: String) = id == myDeviceId

    suspend fun transferPlayback(context: Context, deviceId: String): Boolean =
        withContext(Dispatchers.Main.immediate) { requestTransfer(context, deviceId) }

    private var transferRequestId = 0L
    private var incomingTransferJob: Job? = null

    private suspend fun requestTransfer(context: Context, deviceId: String): Boolean {
        val requestId = ++transferRequestId
        transferError = null
        if (!registered || myDeviceId.isBlank() || devices.value.none { it.id == deviceId }) return false
        if (isLocalDevice(deviceId)) readCluster(context)
        if (requestId != transferRequestId) return false
        if (transferConfirmed(deviceId)) return true
        val source = if (isLocalDevice(deviceId)) {
            clusterActiveDevice.value.ifBlank { return false }
        } else {
            myDeviceId
        }
        val body = JSONObject()
            .put("transfer_options", JSONObject().put("restore_paused", "restore"))
            .put("command_id", UUID.randomUUID().toString().replace("-", ""))
        val response = Net.postJson(
            "https://$spclientHost/connect-state/v1/connect/transfer/from/$source/to/$deviceId",
            body.toString(), headers(context), "transfer",
        )
        Log.i("Connect", "Transfer to ${if (isLocalDevice(deviceId)) "phone" else "remote"} HTTP ${response?.code}")
        if (requestId != transferRequestId || response?.ok != true) return false
        repeat(5) {
            readCluster(context)
            if (requestId != transferRequestId) return false
            if (transferConfirmed(deviceId)) return true
            delay(700)
        }
        if (requestId != transferRequestId) return false
        if (isLocalDevice(deviceId)) incomingTransferJob?.join()
        if (requestId != transferRequestId) return false
        if (!isLocalDevice(deviceId) || clusterActiveDevice.value != source || engine?.current() != null || transferError != null) {
            return transferConfirmed(deviceId)
        }
        val remote = pendingRemote ?: return false
        startRemoteOnPhone(remote, autoplay = false, provisional = true)
        val loadId = engine?.state?.value?.loadId ?: return false
        return confirmLocalTransfer(context, loadId, remote.playing, requestId)
    }

    private suspend fun confirmLocalTransfer(
        context: Context,
        loadId: Long,
        resume: Boolean,
        requestId: Long = transferRequestId,
    ): Boolean {
        repeat(40) {
            delay(500)
            val current = engine ?: return false
            val state = current.state.value
            if (requestId != transferRequestId || state.loadId != loadId) {
                // Superseded mid-transfer: the provisional queue is still on
                // screen and stuck waiting for ownership; stop it instead of
                // leaving play/pause dead until the next queue load.
                if (requestId != transferRequestId && state.loadId == loadId) {
                    current.stop(loadId, keepLastLocal = true)
                }
                return false
            }
            if (state.error != null) {
                if (state.error == PukPlayer.LICENSE_RATE_LIMITED) transferError = state.error
                playbackTransferredAway = true
                current.stop(loadId, keepLastLocal = true)
                return false
            }
            if (!state.buffering && state.licenseReady) {
                readCluster(context)
                if (requestId != transferRequestId || current.state.value.loadId != loadId) {
                    if (requestId != transferRequestId && current.state.value.loadId == loadId) {
                        current.stop(loadId, keepLastLocal = true)
                    }
                    return false
                }
                if (transferConfirmed(myDeviceId)) {
                    current.confirmConnectOwnership(loadId, resume)
                    return true
                }
            }
        }
        if (requestId == transferRequestId && engine?.state?.value?.loadId == loadId) {
            playbackTransferredAway = true
            engine?.stop(loadId, keepLastLocal = true)
        }
        return false
    }

    private fun transferConfirmed(deviceId: String): Boolean =
        clusterActiveDevice.value == deviceId && (!isLocalDevice(deviceId) ||
            engine?.state?.value?.let { it.licenseReady && !it.buffering && it.error == null } == true)

    private val remoteTrackState = MutableStateFlow<RemoteTrack?>(null)
    val remoteTrack = remoteTrackState.asStateFlow()

    @Volatile private var clusterRevision = 0L
    @Volatile private var pendingRemote: RemotePlayback? = null
    private data class RemotePlayback(val item: PukPlayer.QueueItem, val position: Long, val playing: Boolean, val capturedAt: Long)

    private fun startRemoteOnPhone(remote: RemotePlayback, autoplay: Boolean = remote.playing, provisional: Boolean = false) {
        val position = remote.position + if (remote.playing) SystemClock.elapsedRealtime() - remote.capturedAt else 0L
        val display = remoteTrackState.value.takeIf { remoteUri == remote.item.uri }
        val item = remote.item.copy(
            title = remote.item.title.ifBlank { display?.title.orEmpty() },
            imageUrl = remote.item.imageUrl.ifBlank { display?.artwork.orEmpty() },
        )
        selectLocalPlayback()
        lastTransferStartAt = SystemClock.elapsedRealtime()
        engine?.playQueue(
            listOf(item), 0, autoplay,
            position.coerceIn(0L, remote.item.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE),
            awaitConnectOwnership = provisional,
        )
    }

    private var remoteUri = ""
    private var metadataJob: Job? = null
    private var lastClusterUpdateAt: Long? = null
    private var lastClusterReadAt: Long? = null
    private var clusterRetryAt = 0L
    private var clusterReadInProgress = false
    private var remoteAvailabilityJob: Job? = null
    // Server often mirrors one transfer as both a cluster push and a command
    // frame; remember what was already applied so playback does not restart.
    private var lastCommandId = ""
    private var lastCommandAt = 0L
    private var lastTransferStartAt = 0L

    suspend fun refreshNowPlaying(context: Context) {
        if (!registered) return
        val lastUpdate = maxOf(lastClusterUpdateAt ?: 0L, lastClusterReadAt ?: 0L)
        val interval = if (clusterActiveDevice.value.isBlank() || isLocalDevice(clusterActiveDevice.value)) 30_000L else 12_000L
        if (lastUpdate != 0L && SystemClock.elapsedRealtime() - lastUpdate < interval) return
        readCluster(context)
    }

    private suspend fun readCluster(context: Context) {
        if (clusterReadInProgress || SystemClock.elapsedRealtime() < clusterRetryAt) return
        clusterReadInProgress = true
        lastClusterReadAt = SystemClock.elapsedRealtime()
        try {
            fetchCluster(context)
        } finally {
            clusterReadInProgress = false
        }
    }

    private suspend fun fetchCluster(context: Context) {
        val revision = clusterRevision
        val response = Net.get(
            "https://$spclientHost/connect-state/v1/cluster",
            headers(context) + ("Accept" to "application/json"),
            "cluster",
        )
        Log.d("Connect", "Cluster refresh HTTP ${response?.code}")
        if (response?.code == 429) {
            val seconds = response.retryAfterSeconds().takeIf { it > 0 } ?: 60L
            clusterRetryAt = SystemClock.elapsedRealtime() + seconds.coerceAtMost(Long.MAX_VALUE / 2_000) * 1_000L
        }
        if (registered && revision == clusterRevision && response?.ok == true) {
            handleCluster(response.body)
        }
    }

    private var engine: PukPlayer? = null
    private var commandJob: Job? = null
    private var stateJob: Job? = null

    /** Boot the connect stack: dealer socket + registrations + state loop. */
    fun start(context: Context) {
        if (commandJob?.isActive == true) return
        myDeviceId = Identity.deviceId(context)
        engine = PlaybackService.engine(context)
        commandJob = scope.launch {
            var attempt = 0
            while (isActive) {
                try {
                    resolveHosts(context)
                    connectDealer(context)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("Connect", "loop: ${e.javaClass.simpleName} ${e.message}")
                }
                attempt++
                delay((1000L shl (attempt - 1).coerceIn(0, 4)).coerceAtMost(20_000L))
            }
        }
    }

    suspend fun stopForAccountChange(context: Context) {
        val jobs = scope.coroutineContext[kotlinx.coroutines.Job]?.children?.toList().orEmpty()
        val unregister = stop(context)
        jobs.forEach { it.cancel() }
        jobs.forEach { it.join() }
        unregister.join()
    }

    fun stop(context: Context): kotlinx.coroutines.Job {
        ++transferRequestId
        incomingTransferJob?.cancel()
        incomingTransferJob = null
        commandJob?.cancel()
        commandJob = null
        stateJob?.cancel()
        stateJob = null
        val unregister = scope.launch {
            runCatching {
                unregister(context)
            }
        }
        socket?.close(1000, null)
        socket = null
        registered = false
        playbackTransferredAway = false
        clusterActiveDevice.value = ""
        remoteAvailabilityJob?.cancel()
        metadataJob?.cancel()
        remoteUri = ""
        pendingRemote = null
        remoteTrackState.value = null
        outputDevices.value = emptyList()
        connectionId = ""
        return unregister
    }

    // ---- bootstrap ----

    private suspend fun resolveHosts(context: Context) {
        val response = Net.get("https://apresolve.spotify.com/?type=dealer-g2&type=spclient", tag = "apresolve")
        val json = response?.json()
        dealerHost = json?.optJSONArray("dealer-g2")?.optString(0).orEmpty().ifBlank { "dealer.spotify.com" }
        spclientHost = json?.optJSONArray("spclient")?.optString(0).orEmpty().ifBlank { "guc3-spclient.spotify.com" }
        Log.i("Connect", "hosts dealer=$dealerHost spclient=$spclientHost")
    }

    fun spclient(): String = spclientHost

    fun selectLocalPlayback() {
        playbackTransferredAway = false
    }

    private suspend fun headers(context: Context): Map<String, String> = buildMap {
        put("Authorization", "Bearer ${TokenMinter.ensure(context)}")
        put("client-token", ClientTokenManager.get(context))
        put("app-platform", "WebPlayer")
        put("spotify-app-version", Net.WEB_PLAYER_VERSION)
    }

    // ---- dealer websocket ----

    private suspend fun connectDealer(context: Context) {
        val token = TokenMinter.ensure(context)
        if (token.isBlank()) throw IllegalStateException("no access token")
        lastFrameAt = SystemClock.elapsedRealtime()
        val request = Request.Builder()
            .url("wss://$dealerHost/?access_token=$token")
            .build()

        val incoming = kotlinx.coroutines.channels.Channel<JSONObject>(64)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i("Connect", "dealer open ${response.code}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastFrameAt = SystemClock.elapsedRealtime()
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (json.optString("type")) {
                    "ping" -> webSocket.send("""{"type":"pong"}""")
                    "pong" -> return
                }
                incoming.trySend(json)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w("Connect", "dealer failure ${t.message} code=${response?.code ?: 0}")
                incoming.close()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i("Connect", "dealer closed $code")
                incoming.close()
            }
        }

        val ws = wsClient.newWebSocket(request, listener)
        socket = ws

        // ping keep-alive
        val pinger = scope.launch {
            while (true) {
                delay(PING_MS)
                if (!ws.send("""{"type":"ping"}""")) break
                if (SystemClock.elapsedRealtime() - lastFrameAt > 3 * PING_MS) {
                    Log.w("Connect", "dealer silent, reconnecting")
                    ws.cancel()
                    break
                }
            }
        }

        // frame pump
        try {
            while (true) {
                val frame = incoming.receiveCatching().getOrNull() ?: break
                absorb(context, frame)
            }
        } finally {
            pinger.cancel()
            ws.cancel()
            registered = false
            stateJob?.cancel()
            stateJob = null
            connectionId = ""
        }
    }

    private suspend fun absorb(context: Context, frame: JSONObject) {
        // message_ident style frames (single compressed payload)
        val ident = frame.optString("message_ident")
        if (ident.isNotBlank()) {
            val key = frame.optString("key")
            val gzip = frame.optJSONObject("headers")?.optString("Transfer-Encoding").equals("gzip", true)
            val payload = when (val body = frame.opt("payload")) {
                is JSONObject -> body.optString("compressed").takeIf { it.isNotBlank() }
                    ?.let { decode(it, gzip) } ?: body.takeUnless { it.has("compressed") }?.toString()
                is String -> if (body.trimStart().startsWith("{")) body else decode(body, gzip)
                else -> null
            }
            if (key.isNotBlank()) {
                val reply = JSONObject().put("type", "reply").put("key", key)
                    .put("payload", JSONObject().put("success", payload != null))
                socket?.send(reply.toString())
            }
            if (payload == null) {
                Log.w("Connect", "Could not decode dealer request")
                return
            }
            handlePayload(context, ident, payload)
            return
        }
        // uri style frames (payloads array)
        val uri = frame.optString("uri")
        if (uri.isBlank()) return
        val headerNode = frame.optJSONObject("headers")
        // the connection frame's header carries the connection id
        if (uri.startsWith(URI_CONNECTIONS)) {
            val fromHeader = headerNode?.keys()?.asSequence()
                ?.firstOrNull { it.contains("connection-id", true) }
                ?.let { headerNode.optString(it) }
            if (!fromHeader.isNullOrBlank()) onConnectionId(context, fromHeader)
        }
        val gzip = headerNode?.optString("Transfer-Encoding").equals("gzip", true)
        val payloads = frame.optJSONArray("payloads") ?: frame.opt("payload")?.let { JSONArray().put(it) } ?: return
        for (i in 0 until payloads.length()) {
            when (val item = payloads.opt(i)) {
                is String -> (if (item.trimStart().startsWith("{")) item else decode(item, gzip))
                    ?.let { handlePayload(context, uri, it) }
                is JSONObject -> handlePayload(context, uri, item.toString())
            }
        }
    }

    private fun decode(base64: String, isGzip: Boolean): String? = runCatching {
        val raw = java.util.Base64.getDecoder().decode(base64)
        val bytes = if (isGzip) java.util.zip.GZIPInputStream(raw.inputStream()).readBytes() else raw
        String(bytes, Charsets.UTF_8)
    }.getOrNull()

    // ---- registration ----

    private suspend fun onConnectionId(context: Context, id: String) {
        connectionId = id
        Log.i("Connect", "connection id ${id.take(16)}…")
        registered = false
        stateJob?.cancel()
        val member = registerMember(context)
        if (member?.ok != true || registerTrackPlayback(context)?.ok != true) {
            throw IllegalStateException("Spotify Connect registration rejected")
        }
        registered = true
        Log.i("Connect", "device registered")
        startStateLoop(context)
        handleCluster(member.body)
        refreshNowPlaying(context)
    }

    private fun deviceInfo(context: Context): JSONObject =
        JSONObject()
            .put("can_play", true)
            .put("volume", 65535)
            .put("name", DEVICE_NAME)
            .put("device_id", Identity.deviceId(context))
            .put("device_type", "COMPUTER")
            .put("device_software_version", Net.WEB_PLAYER_VERSION)
            .put("brand", "spotify")
            .put("model", "web_player")
            .put(
                "capabilities",
                JSONObject()
                    .put("can_be_player", true)
                    .put("is_controllable", true)
                    .put("is_observable", true)
                    .put("needs_full_player_state", true)
                    .put(
                        "supported_types",
                        JSONArray()
                            .put("audio/track")
                            .put("audio/episode")
                            .put("audio/ad"),
                    ),
            )

    private suspend fun registerMember(context: Context): Net.Response? {
        val url = "https://$spclientHost/connect-state/v1/devices/${Identity.deviceId(context)}"
        val headers = headers(context) + mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "X-Spotify-Connection-Id" to connectionId,
        )
        val body = JSONObject()
            .put("member_type", "CONNECT_STATE")
            .put("put_state_reason", "NEW_DEVICE")
            .put("is_active", false)
            .put("client_side_timestamp", System.currentTimeMillis())
            .put(
                "device",
                JSONObject().put(
                    "device_info",
                    deviceInfo(context),
                ),
            )
            .toString()
        val response = Net.putJson(url, body, headers, "member")
        Log.i("Connect", "member register -> ${response?.code ?: "no response"}")
        return response
    }

    private suspend fun registerTrackPlayback(context: Context): Net.Response? {
        val url = "https://$spclientHost/track-playback/v1/devices"
        val headers = headers(context) + mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "Origin" to "https://open.spotify.com",
            "Referer" to "https://open.spotify.com/",
            "User-Agent" to Net.DESKTOP_UA,
        )
        val device = JSONObject()
            .put("brand", "spotify")
            .put("model", "web_player")
            .put("name", DEVICE_NAME)
            .put("device_id", Identity.deviceId(context))
            .put("device_type", "COMPUTER")
            .put("platform_name", "web_player")
            .put("platform_identifier", "web_player windows 10;chrome 150.0.0.0;desktop")
            .put("is_group", false)
            .put("is_public", false)
            .put("correlation_id", Identity.deviceId(context))
            .put("client_version", "harmony:4.81.1-1f3d48b7f")
            .put("metadata", JSONObject())
            .put(
                "capabilities",
                JSONObject()
                    .put("can_be_player", true)
                    .put("is_controllable", true)
                    .put("is_observable", true)
                    .put("needs_full_player_state", true),
            )
        val body = JSONObject()
            .put("device", device)
            .put("outro_endcontent_snooping", false)
            .put("connection_id", connectionId)
            .put("client_version", "harmony:4.81.1-1f3d48b7f")
            .put("volume", 65535)
            .toString()
        val response = Net.postJson(url, body, headers, "register")
        Log.i("Connect", "track-playback register -> ${response?.code ?: "no response"}")
        return response
    }

    private suspend fun unregister(context: Context) {
        val url = "https://$spclientHost/connect-state/v1/devices/${Identity.deviceId(context)}"
        Net.delete(url, headers(context) + mapOf("Accept" to "application/json"), "member-delete")
    }

    // ---- state publishing ----

    private fun startStateLoop(context: Context) {
        stateJob?.cancel()
        stateJob = scope.launch {
            var lastKey = ""
            var lastPublish = 0L
            var retryAt = 0L
            while (isActive) {
                val engineNow = engine ?: break
                val state = engineNow.state.value
                val item = state.queue.getOrNull(state.index)
                val key = "${state.loadId}|${state.playing}|${state.seekRevision}"
                val now = SystemClock.elapsedRealtime()
                if (playbackTransferredAway || item == null || !state.licenseReady || state.buffering || state.error != null) {
                    lastKey = ""
                } else if (registered && now >= retryAt && (key != lastKey || now - lastPublish >= STATE_REFRESH_MS)) {
                    val response = publishState(context, state, item)
                    if (response?.ok == true) {
                        lastKey = key
                        lastPublish = now
                    } else if (response?.code == 429) {
                        val seconds = response.retryAfterSeconds().takeIf { it > 0 } ?: 60L
                        retryAt = SystemClock.elapsedRealtime() + seconds.coerceAtMost(Long.MAX_VALUE / 2_000) * 1_000L
                    }
                }
                delay(STATE_POLL_MS)
            }
        }
    }

    private suspend fun publishState(context: Context, state: PukPlayer.State, item: PukPlayer.QueueItem): Net.Response? {
        val playing = state.playing
        val trackNode = JSONObject()
            .put("uri", item.uri)
            .put("provider", "context")
            .put("metadata", JSONObject()
                .put("title", item.title)
                .put("artist_name", item.artist)
                .put("image_url", item.imageUrl))
        val contextUri = item.albumUri.ifBlank { item.uri }
        val playerState = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("position_as_of_timestamp", state.positionMs)
            .put("duration", state.durationMs)
            .put("is_playing", true)
            .put("is_paused", !playing)
            .put("is_buffering", state.buffering)
            .put("playback_speed", if (playing) 1 else 0)
            .put("session_id", sessionId)
            .put("context_uri", contextUri)
            .put("context_url", "context://$contextUri")
            .put("track", trackNode)
            .put("index", JSONObject().put("page", 0).put("track", state.index))
        val url = "https://$spclientHost/connect-state/v1/devices/${Identity.deviceId(context)}"
        val headers = headers(context) + mapOf(
            "Accept" to "application/json",
            "Content-Type" to "application/json",
            "X-Spotify-Connection-Id" to connectionId,
        )
        val body = JSONObject()
            .put("member_type", "CONNECT_STATE")
            .put("put_state_reason", "PLAYER_STATE_CHANGED")
            .put("is_active", true)
            .put("client_side_timestamp", System.currentTimeMillis())
            .put(
                "device",
                JSONObject()
                    .put("device_info", deviceInfo(context))
                    .put("player_state", playerState),
            )
            .toString()
        val latest = engine?.state?.value
        if (playbackTransferredAway || latest?.loadId != state.loadId ||
            !latest.licenseReady || latest.error != null || latest.seekRevision != state.seekRevision ||
            latest.playing != state.playing
        ) return null
        val response = Net.putJson(url, body, headers, "state")
        if (response?.ok != true) Log.w("Connect", "state publish HTTP ${response?.code}")
        else Log.i("Connect", "state accepted playing=$playing")
        return response
    }

    // ---- incoming commands ----

    private suspend fun handlePayload(context: Context, uri: String, payload: String) {
        when {
            uri.startsWith(URI_CONNECTIONS) -> Unit // handled via header
            uri.startsWith(URI_COMMAND) ||
                uri.startsWith("hm://connect-state/v1/player/command") ||
                uri.startsWith("hm://connect-state/v1/connect/transfer") -> runCatching { handleCommand(context, payload, uri.startsWith("hm://connect-state/v1/connect/transfer")) }
                .onFailure { Log.w("Connect", "command failed: ${it.message}") }
            uri.startsWith(URI_CLUSTER) -> runCatching { handleCluster(payload) }
                .onFailure { Log.w("Connect", "cluster parse failed") }
        }
    }

    private fun handleCluster(payload: String) {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val cluster = json.optJSONObject("cluster") ?: json
        cluster.optJSONObject("devices")?.let { devices ->
            outputDevices.value = devices.keys().asSequence().mapNotNull { id ->
                val device = devices.optJSONObject(id) ?: return@mapNotNull null
                val name = device.optString("name")
                if (id.startsWith("hobs_") || name.isBlank() || device.optBoolean("is_restricted")) null
                else OutputDevice(id, name)
            }.toList()
        }
        val owner = when {
            json.has("active_device_id") -> json
            cluster.has("active_device_id") -> cluster
            else -> return
        }
        val activeDeviceId = owner.optString("active_device_id", "").takeUnless { it == "null" }.orEmpty()
        lastClusterUpdateAt = SystemClock.elapsedRealtime()
        remoteAvailabilityJob?.cancel()
        if (activeDeviceId.isNotBlank() && activeDeviceId != myDeviceId) {
            remoteAvailabilityJob = scope.launch {
                delay(45_000)
                pendingRemote = null
                showRemoteTrack("", "", "", "", "")
            }
        } else {
            remoteAvailabilityJob = null
        }
        val previousDevice = clusterActiveDevice.value
        clusterActiveDevice.value = activeDeviceId
        clusterRevision++
        val playerState = cluster.optJSONObject("player_state")
        if (activeDeviceId == myDeviceId && previousDevice != myDeviceId &&
            playbackTransferredAway && engine?.current() == null &&
            com.project.puk.jam.JamController.activeController?.isActive != true
        ) {
            pendingRemote?.let(::startRemoteOnPhone)
        }
        if (activeDeviceId.isBlank() || activeDeviceId == myDeviceId) {
            pendingRemote = null
            showRemoteTrack("", "", "", "", "")
        } else if (playerState != null) {
            val track = playerState.optJSONObject("track")
            val metadata = track?.optJSONObject("metadata")
            val uri = track?.optString("uri").orEmpty()
            pendingRemote = if (uri.startsWith("spotify:track:")) {
                val display = remoteTrackState.value.takeIf { remoteUri == uri && previousDevice == activeDeviceId }
                val playing = playerState.optBoolean("is_playing", true) &&
                    !playerState.optBoolean("is_paused") && !playerState.optBoolean("is_buffering")
                val timestamp = playerState.optLong("timestamp")
                val age = System.currentTimeMillis() - timestamp
                val position = playerState.optLong("position_as_of_timestamp").coerceAtLeast(0)
                RemotePlayback(
                    PukPlayer.QueueItem(
                        uri = uri,
                        title = metadata?.optString("title").orEmpty().ifBlank { display?.title.orEmpty() },
                        artist = metadata?.optString("artist_name").orEmpty().ifBlank { display?.artist.orEmpty() },
                        albumUri = playerState.optString("context_uri").takeIf { it.startsWith("spotify:album:") }.orEmpty(),
                        imageUrl = display?.artwork.orEmpty(),
                        durationMs = playerState.optLong("duration"),
                    ),
                    position + if (playing && age in 0L..600_000L) age else 0L,
                    playing,
                    SystemClock.elapsedRealtime(),
                )
            } else null
            showRemoteTrack(
                uri = track?.optString("uri").orEmpty(),
                title = metadata?.optString("title").orEmpty().ifBlank { metadata?.optString("name").orEmpty() },
                artist = metadata?.optString("artist_name").orEmpty(),
                artwork = metadata?.optString("image_url").orEmpty().ifBlank {
                    metadata?.optString("image_large_url").orEmpty()
                }.replace("spotify:image:", "https://i.scdn.co/image/"),
                device = cluster.optJSONObject("devices")?.optJSONObject(activeDeviceId)
                    ?.optString("name").orEmpty(),
            )
        } else if (activeDeviceId != previousDevice) {
            showRemoteTrack("", "", "", "", "")
        }
        if (activeDeviceId.isNotBlank() && activeDeviceId != myDeviceId &&
            activeDeviceId != previousDevice && !playbackTransferredAway
        ) {
            playbackTransferredAway = true
            engine?.stop(keepLastLocal = true)
            Log.i("Connect", "Playback moved to another device")
        }
    }

    @Synchronized
    private fun showRemoteTrack(uri: String, title: String, artist: String, artwork: String, device: String) {
        if (uri.isNotBlank() && uri == remoteUri && title.isBlank()) {
            val previous = remoteTrackState.value
            if (previous != null) {
                remoteTrackState.value = previous.copy(device = device.ifBlank { previous.device })
                return
            }
            if (metadataJob?.isActive == true) return
        }
        metadataJob?.cancel()
        remoteUri = uri
        remoteTrackState.value = if (title.isNotBlank()) RemoteTrack(title, artist, artwork, device) else null
        val id = uri.removePrefix("spotify:track:")
        if (title.isNotBlank() || !id.matches(Regex("[a-zA-Z0-9]{22}"))) return
        metadataJob = scope.launch {
            val response = Net.get(
                "https://open.spotify.com/oembed?url=https%3A%2F%2Fopen.spotify.com%2Ftrack%2F$id",
                tag = "remote-track",
            )
            val metadata = response?.takeIf { it.ok }?.json() ?: return@launch
            val name = metadata.optString("title").takeIf { it.isNotBlank() } ?: return@launch
            synchronized(this@Connect) {
                if (remoteUri == uri && remoteTrackState.value == null) {
                    remoteTrackState.value = RemoteTrack(name, artist, metadata.optString("thumbnail_url"), device)
                    Log.i("Connect", "Remote track metadata loaded")
                }
            }
        }
    }

    private suspend fun handleCommand(context: Context, payload: String, transferFrame: Boolean) {
        val engineNow = engine ?: return
        // While a Jam session is active the Jam server is the single source of
        // truth for playback: Spotify-cluster commands — including echoes of
        // our own published state — must not drive the engine, or they fight
        // the Jam sync (seek storms, queue restarts after pause).
        if (com.project.puk.jam.JamController.activeController?.isActive == true) {
            Log.i("Connect", "jam active — remote command ignored")
            return
        }
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val command = json.optJSONObject("command") ?: json
        val commandId = json.optString("command_id").ifBlank { command.optString("command_id") }
        val now = SystemClock.elapsedRealtime()
        if (commandId.isNotBlank() && commandId == lastCommandId && now - lastCommandAt < 30_000) {
            Log.i("Connect", "duplicate command ${commandId.take(8)} ignored")
            return
        }
        if (commandId.isNotBlank()) {
            lastCommandId = commandId
            lastCommandAt = now
        }
        val endpoint = command.optString("endpoint")
        val options = command.optJSONObject("options") ?: JSONObject()
        val fromBodyTransfer = transferFrame || endpoint == "transfer" || command.has("transfer_options")
        val machine = json.optJSONObject("state_machine") ?: command.optJSONObject("state_machine")
        val hasPlaybackState = machine != null && (fromBodyTransfer ||
            endpoint == "play" || endpoint == "replace_state" || endpoint.isBlank())

        Log.i("Connect", "command '$endpoint'")
        if (playbackTransferredAway && !fromBodyTransfer && endpoint != "play" && !hasPlaybackState) return

        when {
            machine != null && hasPlaybackState -> {
                playRemoteQueue(context, json, command, machine)
            }
            fromBodyTransfer -> transfer(context, command)
            endpoint == "play" -> {
                val contextUri = command.optJSONObject("context")?.optString("uri").orEmpty()
                val skipUri = options.optJSONObject("skip_to")?.optString("track_uri").orEmpty()
                playContext(context, contextUri, skipUri)
            }
            endpoint == "pause" -> engineNow.pause()
            endpoint == "resume" -> engineNow.resume()
            endpoint == "seek_to" -> {
                val ms = options.optJSONObject("position_and_preview")
                    ?.optLong("position_ms", -1L) ?: -1L
                if (ms >= 0) engineNow.seekTo(ms)
            }
            endpoint == "skip_next" -> engineNow.next()
            endpoint == "skip_prev" -> engineNow.previous()
        }
    }

    private fun playRemoteQueue(context: Context, payload: JSONObject, command: JSONObject, machine: JSONObject) {
        val tracks = machine.optJSONArray("tracks") ?: return
        val states = machine.optJSONArray("states") ?: return
        val reference = payload.optJSONObject("updated_state_ref") ?: payload.optJSONObject("state_ref")
            ?: command.optJSONObject("updated_state_ref") ?: command.optJSONObject("state_ref")
        val stateIndex = reference?.optInt("state_index", 0) ?: 0
        val queue = mutableListOf<PukPlayer.QueueItem>()
        var startIndex = -1
        for (index in 0 until states.length()) {
            val state = states.optJSONObject(index) ?: continue
            val metadata = tracks.optJSONObject(state.optInt("track", -1))
                ?.optJSONObject("metadata") ?: continue
            val uri = metadata.optString("uri")
            if (!uri.startsWith("spotify:track:")) continue
            if (index == stateIndex) startIndex = queue.size
            val authors = metadata.optJSONArray("authors")
            val artist = if (authors == null) "" else (0 until authors.length())
                .mapNotNull { authors.optJSONObject(it)?.optString("name") }
                .joinToString(", ")
            queue.add(PukPlayer.QueueItem(
                uri = uri,
                title = metadata.optString("name"),
                artist = artist,
                albumUri = metadata.optString("group_uri"),
                imageUrl = metadata.optJSONArray("images")?.optJSONObject(0)?.optString("url").orEmpty(),
                durationMs = metadata.optLong("duration"),
            ))
        }
        if (startIndex < 0) return
        val existing = engine?.current()
        if (existing != null && existing.uri == queue[startIndex].uri &&
            SystemClock.elapsedRealtime() - lastTransferStartAt < 5_000
        ) {
            // The cluster push already started this track; replaying the
            // command frame would restart the queue at a reset position.
            Log.i("Connect", "transfer already applied, ignoring duplicate start of ${existing.uri}")
            return
        }
        val position = payload.optLong("seek_to", command.optLong("seek_to",
            states.optJSONObject(stateIndex)?.optLong("initial_playback_position") ?: 0L))
        val resume = reference?.optBoolean("paused") != true
        val waitForOwnership = clusterActiveDevice.value.let { it.isNotBlank() && it != myDeviceId }
        selectLocalPlayback()
        engine?.playQueue(
            queue, startIndex, resume && !waitForOwnership, position.coerceAtLeast(0L),
            awaitConnectOwnership = waitForOwnership,
        )
        if (waitForOwnership) confirmIncomingTransfer(context, resume)
    }

    private fun confirmIncomingTransfer(context: Context, resume: Boolean) {
        val loadId = engine?.state?.value?.loadId ?: return
        incomingTransferJob?.cancel()
        incomingTransferJob = scope.launch { confirmLocalTransfer(context, loadId, resume) }
    }

    private fun transfer(context: Context, command: JSONObject) {
        // A transfer to this device lands as options with play_on_transfer
        val playOnTransfer = command.optJSONObject("transfer_options")
            ?.optBoolean("play_on_transfer", true) ?: true
        Log.i("Connect", "transfer here, play=$playOnTransfer")
        sessionId = UUID.randomUUID().toString()
        val remote = pendingRemote
        if (remote != null && clusterActiveDevice.value.let { it.isNotBlank() && it != myDeviceId }) {
            startRemoteOnPhone(remote, autoplay = false, provisional = true)
            confirmIncomingTransfer(context, playOnTransfer && remote.playing)
            return
        }
        if (engine?.current() != null) {
            selectLocalPlayback()
            if (playOnTransfer) engine?.resume() else engine?.pause()
        } else {
            pendingRemote?.let { remote ->
                startRemoteOnPhone(if (playOnTransfer) remote else remote.copy(playing = false))
            }
        }
    }

    /** Loads a playlist/album/liked context and starts playback at skipUri. */
    private suspend fun playContext(context: Context, contextUri: String, skipUri: String) {
        sessionId = UUID.randomUUID().toString()
        val items = when {
            contextUri.startsWith("spotify:playlist:") ->
                Spotify.playlist(context, contextUri)?.tracks?.map { it.toQueueItem() }
            contextUri.startsWith("spotify:album:") ->
                Spotify.album(context, contextUri)?.tracks?.map { it.toQueueItem() }
            contextUri.startsWith("spotify:collection:") || contextUri.contains("collection") ->
                Spotify.likedTracks(context).map { it.toQueueItem() }
            else -> null
        } ?: run {
            Log.w("Connect", "cannot load context $contextUri")
            return
        }
        if (items.isEmpty()) return
        val startIndex = items.indexOfFirst { it.uri == skipUri }.coerceAtLeast(0)
        selectLocalPlayback()
        engine?.playQueue(items, startIndex)
    }

    private fun com.project.puk.api.TrackRow.toQueueItem(): PukPlayer.QueueItem =
        PukPlayer.QueueItem(
            uri = uri,
            title = name,
            artist = artists,
            albumUri = albumUri,
            imageUrl = imageUrl,
            durationMs = durationMs,
        )
}
