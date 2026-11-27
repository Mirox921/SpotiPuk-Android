package com.project.puk.jam

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * WebSocket transport to the Jam backend. Reuses the proven Connect.kt
 * pattern: single reader loop, ping keep-alive with stale detection,
 * exponential reconnect backoff, AUTH as the very first frame so the
 * participant token never lands in URLs or logs (owner correction #5).
 */
class JamClient(
    private val scope: CoroutineScope,
    private val listener: Listener,
    private val serverUrl: String,
) {

    interface Listener {
        fun onEnvelope(envelope: JamEnvelope)
        fun onConnectionStateChanged(state: ConnectionState)
        fun onAuthFailed(code: String)

        /** Сервер закрыл сокет по своей инициативе (kicked и др.). */
        fun onServerClose(reason: String) {}
    }

    enum class ConnectionState { CONNECTING, CONNECTED, RECONNECTING, OFFLINE }

    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private var state = ConnectionState.OFFLINE
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var sessionId = ""
    private var participantId = ""
    private var token = ""
    private var wantConnection = false

    /** Monotonic time of the last frame from the server (liveness watchdog). */
    @Volatile
    var lastFrameAtElapsedMs: Long = 0L
        private set

    fun configure(sessionId: String, participantId: String, token: String) {
        this.sessionId = sessionId
        this.participantId = participantId
        this.token = token
    }

    fun connect() {
        wantConnection = true
        openSocket()
    }

    fun disconnect() {
        wantConnection = false
        reconnectJob?.cancel()
        runCatching { socket?.close(1000, "bye") }
        socket = null
        updateState(ConnectionState.OFFLINE)
    }

    /** Sends a command; returns the generated eventId (null when offline). */
    fun send(type: String, eventId: String = UUID.randomUUID().toString(), payload: JSONObject = JSONObject()): String? {
        val socket = socket ?: run { Log.w("Jam", "send $type: no socket"); return null }
        val ok = socket.send(JamEnvelope(type = type, eventId = eventId, payload = payload).encode())
        Log.i("Jam", "send $type ok=$ok")
        return if (ok) eventId else null
    }

    fun sendPing(sentElapsed: Long) {
        send(JamEnvelope.CMD_PING, payload = JSONObject().put("t", sentElapsed))
    }

    private fun openSocket() {
        if (!wantConnection) return
        updateState(if (reconnectAttempt == 0) ConnectionState.CONNECTING else ConnectionState.RECONNECTING)
        val wsUrl = serverUrl.replaceFirst("http", "ws") + "/v1/sessions/$sessionId/ws"
        Log.i("Jam", "ws open #$reconnectAttempt $wsUrl")
        val request = Request.Builder().url(wsUrl).build()
        socket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                // AUTH is the very first frame: the token stays off the URL
                // and therefore out of access logs (correction #5).
                socket = webSocket
                send(JamEnvelope.CMD_AUTH, payload = JSONObject()
                    .put("participantId", participantId)
                    .put("token", token))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastFrameAtElapsedMs = android.os.SystemClock.elapsedRealtime()
                val envelope = JamEnvelope.decode(text)
                if (envelope == null) {
                    Log.w("Jam", "decode failed len=${text.length}: ${text.take(160)}")
                    return
                }
                when (envelope.type) {
                    JamEnvelope.EV_AUTH_OK -> updateState(ConnectionState.CONNECTED)
                    // The snapshot both proves the connection and carries the
                    // session state — the controller must still see it.
                    JamEnvelope.EV_SNAPSHOT -> {
                        updateState(ConnectionState.CONNECTED)
                        listener.onEnvelope(envelope)
                    }
                    JamEnvelope.EV_AUTH_FAILED -> {
                        listener.onAuthFailed(envelope.payload.optString("code", "auth_invalid"))
                        // Wrong token is not retryable: stop reconnecting.
                        wantConnection = false
                    }
                    else -> listener.onEnvelope(envelope)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("Jam", "ws failure: ${t.javaClass.simpleName}: ${t.message}")
                if (!wantConnection) return
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i("Jam", "ws closed code=$code reason=$reason")
                if (!wantConnection) return
                // Инициативное закрытие сервера (kicked / session ended /
                // host left): KICKED-кадр обычно уже дошёл, но если он
                // проиграл гонку — сообщаем причину сразу, чтобы кикнутый
                // клиент не висел на старом интерфейсе сессии (владелец).
                if (reason == "kicked") listener.onServerClose(reason)
                // Переподключаемся при любом серверном закрытии: повторный
                // AUTH упадёт (токен удалён) и onAuthFailed приведёт UI в
                // чистое состояние вместо застрявшего экрана сессии.
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        updateState(ConnectionState.RECONNECTING)
        reconnectAttempt++
        // Exponential backoff capped at 30s (spec section 8).
        val backoff = (1_000L shl (reconnectAttempt - 1).coerceAtMost(5)).coerceAtMost(30_000L)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoff)
            if (isActive && wantConnection) openSocket()
        }
    }

    /**
     * Foreground resume: skip the backoff and probe immediately. A socket
     * that died in the background may sit in CONNECTING/RECONNECTING for up
     * to 30 s, and an idle-but-dead TCP connection can look CONNECTED for
     * minutes — [onAppResume] (controller side) decides which case applies.
     */
    fun reconnectNow() {
        if (!wantConnection) return
        reconnectJob?.cancel()
        runCatching { socket?.close(4000, "resume-reconnect") }
        socket = null
        reconnectAttempt = 0
        openSocket()
    }

    /** True when the socket LOOKS connected but no frame arrived recently. */
    fun isStale(staleAfterMs: Long): Boolean =
        state == ConnectionState.CONNECTED && wantConnection &&
            lastFrameAtElapsedMs > 0 &&
            android.os.SystemClock.elapsedRealtime() - lastFrameAtElapsedMs > staleAfterMs

    private fun updateState(next: ConnectionState) {
        if (state != next) {
            state = next
            listener.onConnectionStateChanged(next)
        }
    }

    fun currentState(): ConnectionState = state

    fun shutdown() {
        disconnect()
        wsClient.dispatcher.executorService.shutdown()
        Log.d("Jam", "client shutdown")
    }
}
