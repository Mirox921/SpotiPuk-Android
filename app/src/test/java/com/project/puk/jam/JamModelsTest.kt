package com.project.puk.jam

import com.project.puk.ui.jamCodeFromLink
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wire contract with jam-server; the codec must never crash on odd input. */
class JamModelsTest {

    @Test
    fun `envelope roundtrip keeps fields`() {
        val original = JamEnvelope(
            type = JamEnvelope.CMD_PLAY,
            eventId = "evt-1",
            payload = JSONObject().put("itemId", "i-1"),
        )
        val decoded = JamEnvelope.decode(original.encode())!!
        assertEquals(JamEnvelope.CMD_PLAY, decoded.type)
        assertEquals("evt-1", decoded.eventId)
        assertEquals("i-1", decoded.payload.optString("itemId"))
    }

    @Test
    fun `decode of garbage is null instead of crash`() {
        assertNull(JamEnvelope.decode("not json at all"))
        assertNull(JamEnvelope.decode(""))
    }

    @Test
    fun `session snapshot parses participants queue and playback`() {
        val session = JamSessionState.parse(
            JSONObject(
                """
                {
                  "sessionId": "s1", "inviteCode": "ABCD2345",
                  "permissions": {"guestsCanAdd": true, "guestsCanEditQueue": false},
                  "participants": [
                    {"id": "p1", "name": "Host", "role": "HOST", "connection": "CONNECTED"},
                    {"id": "p2", "name": "Guest", "role": "GUEST", "connection": "DISCONNECTED"}
                  ],
                  "queue": [
                    {"itemId": "i1", "uri": "spotify:track:aaaaaaaaaaaaaaaaaaaaaaaa", "title": "T1", "addedBy": "p1"}
                  ],
                  "playback": {"state": "PAUSED", "itemId": "i1", "index": 0, "positionMs": 42,
                               "stateVersion": 7, "queueVersion": 3}
                }
                """.trimIndent(),
            ),
            myParticipantId = "p2",
        )
        assertEquals(2, session.participants.size)
        assertEquals(1, session.queue.size)
        assertEquals(7, session.playback.stateVersion)
        // Equal rights: guests control playback and edit the queue as well.
        assertTrue(session.canAddTracks)
        assertTrue(session.canControlPlayback)
        assertTrue(session.canEditQueue)
        assertEquals("p2", session.me()?.id)
        assertFalse(session.me()!!.isHost)
    }

    @Test
    fun `stale playback versions are dropped`() {
        // Simulates the guard indirectly: JamSession applies only fresher versions.
        val session = JamSession().apply { clock = System::currentTimeMillis }
        session.applySnapshot(
            JSONObject().put(
                "session",
                JSONObject()
                    .put("sessionId", "s1")
                    .put("playback", JSONObject().put("stateVersion", 5).put("queueVersion", 2)),
            ),
            myParticipantId = "me",
        )
        assertNull(session.applyPlaybackUpdate(JSONObject().put("stateVersion", 3), "PLAYING", 0, null))
        val applied = session.applyPlaybackUpdate(JSONObject().put("stateVersion", 6), "PLAYING", 100, null)
        assertEquals("PLAYING", applied?.state)
        assertEquals(6, session.appliedStateVersion)
    }

    @Test
    fun `stale queue versions are dropped`() {
        val session = JamSession().apply { clock = System::currentTimeMillis }
        session.applySnapshot(
            JSONObject().put(
                "session",
                JSONObject().put("sessionId", "s1").put("playback", JSONObject().put("queueVersion", 4)),
            ),
            myParticipantId = "me",
        )
        assertNull(session.applyQueueUpdated(JSONObject().put("queueVersion", 2)))
        // The newer version is accepted (the queue body itself arrives via SNAPSHOT).
        assertTrue(session.applyQueueUpdated(JSONObject().put("queueVersion", 5)) != null)
    }

    @Test
    fun `only track uris are supported`() {
        assertTrue(isSupportedTrackUri("spotify:track:4uLU6hMCjMI75M1A2tKUQC"))
        assertFalse(isSupportedTrackUri("spotify:episode:2IJLeSC8MyPnWm3VYDjceA"))
        assertFalse(isSupportedTrackUri("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC"))
    }

    @Test
    fun `snapshot anchors the effective position for reconnecting guests`() {
        val session = JamSession().apply { clock = System::currentTimeMillis }
        val now = System.currentTimeMillis()
        val state = session.applySnapshot(
            JSONObject()
                .put("session", JSONObject()
                    .put("sessionId", "s1")
                    .put("playback", JSONObject()
                        .put("state", "PLAYING")
                        .put("positionMs", 0L) // stale stored value at updatedAt
                        .put("startedAtServerMs", now - 138_000L)
                        .put("updatedAt", now)
                        .put("stateVersion", 7)
                        .put("queueVersion", 3)))
                .put("participantId", "me")
                // Server-computed effective position ≈ 2:18 (owner bug: UI
                // showed 0:00 while the track had been playing for 2:18).
                .put("effectivePositionMs", 138_000L)
                .put("serverNowMs", now),
            myParticipantId = "me",
        )
        // Кадр стареет на 0–2 мс между замером теста и якорем — допуск.
        assertTrue(state.playback.positionMs in 138_000..138_010)
        assertTrue(session.appliedPositionMs in 138_000..138_010)
        assertTrue(session.appliedPositionAtElapsedMs > 0)
        // Mid-song join: "start" is anchored to now, not to the past moment.
        assertTrue(session.startAtElapsedMs > 0)
    }

    @Test
    fun `snapshot without effective fields falls back to stored position`() {
        val session = JamSession().apply { clock = System::currentTimeMillis }
        val state = session.applySnapshot(
            JSONObject()
                .put("session", JSONObject()
                    .put("sessionId", "s1")
                    .put("playback", JSONObject().put("state", "PAUSED").put("positionMs", 42_000L).put("stateVersion", 2))),
            myParticipantId = "me",
        )
        assertEquals(42_000L, state.playback.positionMs)
    }

    @Test
    fun `invite codes parse from both link forms`() {
        assertEquals("ABCDEF23", jamCodeFromLink("https://jam.spotipuk.stream/j/ABCDEF23"))
        assertEquals("ABCDEF23", jamCodeFromLink("https://jam.spotipuk.stream/j/abcdef23"))
        assertEquals("ABCDEF23", jamCodeFromLink("spotipuk://jam/join/ABCDEF23"))
        assertEquals("ABCDEF23", jamCodeFromLink("https://jam.spotipuk.stream/j/ABCDEF23?utm=x"))
        assertTrue(jamCodeFromLink("https://example.com/other").isEmpty() || !jamCodeFromLink("https://example.com/other").matches(Regex("[A-HJ-NP-Z2-9]{8}")))
    }

    @Test
    fun `join errors map to readable russian`() {
        assertEquals(
            "Срок действия приглашения истёк. Попросите у ведущего новую ссылку",
            jamJoinErrorMessage("invite_expired", "Invite has expired"),
        )
        assertEquals("Приглашение не найдено — возможно, Jam уже завершён", jamJoinErrorMessage("session_not_found", ""))
        assertEquals("Ведущий отозвал эту ссылку приглашения", jamJoinErrorMessage("invite_revoked", null))
        assertEquals("custom", jamJoinErrorMessage("unknown", "custom"))
        assertEquals("Не удалось подключиться к Jam", jamJoinErrorMessage("unknown", ""))
    }

    @Test
    fun `equal rights are granted to every participant`() {
        val state = JamSessionState.parse(
            JSONObject()
                .put("sessionId", "s1")
                .put("permissions", JSONObject().put("guestsCanControlPlayback", false).put("guestsCanEditQueue", false))
                .put("participants", org.json.JSONArray().put(JSONObject().put("id", "me").put("role", "GUEST")))
                .put("queue", org.json.JSONArray())
                .put("playback", JSONObject().put("state", "PAUSED")),
            myParticipantId = "me",
        )
        assertTrue(state.canControlPlayback)
        assertTrue(state.canAddTracks)
        assertTrue(state.canEditQueue)
        // Устаревшие флаги больше не ограничивают права (равные права 2026-11-20).
    }
}
