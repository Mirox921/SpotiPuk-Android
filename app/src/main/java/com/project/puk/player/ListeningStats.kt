package com.project.puk.player

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID
import com.project.puk.api.Card

internal data class ListeningSlice(
    val account: String,
    val session: String,
    val track: PukPlayer.QueueItem,
    val contextUri: String?,
    val startMs: Long,
    val endMs: Long,
    val listenedMs: Long,
)

internal class ListeningStats(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "listening_stats.db", null, 1) {

    // Raw slices accumulate at ~12/min of listening; keep 13 weeks of history.
    private val retentionMs = 13L * 7 * 24 * 60 * 60 * 1000
    private var lastRotationAt = 0L

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE listening (
                id INTEGER PRIMARY KEY, account TEXT NOT NULL, session TEXT NOT NULL,
                uri TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, artist_uri TEXT NOT NULL,
                album TEXT NOT NULL, artwork TEXT NOT NULL, context_uri TEXT,
                started INTEGER NOT NULL, ended INTEGER NOT NULL, listened INTEGER NOT NULL
            )""",
        )
        db.execSQL("CREATE INDEX listening_account_started ON listening(account, started)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun record(slice: ListeningSlice) {
        writableDatabase.insertOrThrow("listening", null, ContentValues().apply {
            put("account", slice.account)
            put("session", slice.session)
            put("uri", slice.track.uri)
            put("title", slice.track.title)
            put("artist", slice.track.artist)
            put("artist_uri", slice.track.artistUri)
            put("album", slice.track.albumUri)
            put("artwork", slice.track.imageUrl)
            put("context_uri", slice.contextUri)
            put("started", slice.startMs)
            put("ended", slice.endMs)
            put("listened", slice.listenedMs)
        })
        // Prune at most every six hours; week() scans only shrink because of it.
        val now = System.currentTimeMillis()
        if (now - lastRotationAt > 6 * 60 * 60 * 1000) {
            lastRotationAt = now
            writableDatabase.execSQL("DELETE FROM listening WHERE ended < ?", arrayOf(now - retentionMs))
        }
    }

    fun week(account: String, start: Long, end: Long): ListeningWeek {
        val tracks = linkedMapOf<String, ListeningRank>()
        val artists = linkedMapOf<String, ListeningRank>()
        readableDatabase.rawQuery(
            """SELECT uri, title, artist, artist_uri, started, ended, listened, artwork
                FROM listening WHERE account = ? AND started < ? AND ended > ?
                ORDER BY started DESC""",
            arrayOf(account, end.toString(), start.toString()),
        ).use { rows ->
            while (rows.moveToNext()) {
                val started = rows.getLong(4)
                val ended = rows.getLong(5)
                val duration = (ended - started).coerceAtLeast(1)
                val overlap = (minOf(end, ended) - maxOf(start, started)).coerceIn(0, duration)
                val heard = rows.getLong(6) * overlap / duration
                if (heard <= 0) continue
                val uri = rows.getString(0)
                val artist = rows.getString(2)
                tracks.add(uri, rows.getString(1), artist, heard, uri, rows.getString(7))
                if (artist.isNotBlank()) artists.add(rows.getString(3).ifBlank { artist }, artist, "", heard, rows.getString(3), "", uri)
            }
        }
        return ListeningWeek(
            tracks.values.sumOf { it.timeMs },
            tracks.size,
            tracks.values.sortedByDescending { it.timeMs }.take(5),
            artists.values.sortedByDescending { it.timeMs }.take(5),
        )
    }

    fun recent(account: String): List<Card> = readableDatabase.rawQuery(
        """SELECT uri, title, artist, artwork FROM listening
            WHERE account = ? AND id IN (
                SELECT MAX(id) FROM listening WHERE account = ? GROUP BY uri
            ) ORDER BY ended DESC LIMIT 30""",
        arrayOf(account, account),
    ).use { rows ->
        buildList {
            while (rows.moveToNext()) {
                add(Card(rows.getString(0), rows.getString(1), rows.getString(2), rows.getString(3), "track"))
            }
        }
    }

    fun history(account: String): List<ListeningHistoryEntry> = readableDatabase.rawQuery(
        """SELECT uri, title, artist, artwork, MAX(ended) AS last_ended FROM listening
            WHERE account = ? GROUP BY uri ORDER BY last_ended DESC LIMIT 100""",
        arrayOf(account),
    ).use { rows ->
        buildList {
            while (rows.moveToNext()) {
                val uri = rows.getString(0)
                add(ListeningHistoryEntry(
                    Card(uri, rows.getString(1), rows.getString(2), rows.getString(3),
                        if (uri.startsWith("spotify:episode:")) "episode" else "track"),
                    rows.getLong(4),
                ))
            }
        }
    }

    fun clear(account: String) {
        writableDatabase.delete("listening", "account = ?", arrayOf(account))
    }

    private fun MutableMap<String, ListeningRank>.add(id: String, title: String, detail: String, heard: Long, uri: String, image: String, sourceUri: String = "") {
        val rank = get(id) ?: ListeningRank(title, detail, 0, uri, image, sourceUri)
        put(id, rank.copy(timeMs = rank.timeMs + heard))
    }
}

internal data class ListeningHistoryEntry(val card: Card, val endedMs: Long)

internal data class ListeningRank(val title: String, val detail: String, val timeMs: Long, val uri: String = "", val imageUrl: String = "", val sourceUri: String = "")
internal data class ListeningWeek(
    val timeMs: Long,
    val uniqueTracks: Int,
    val tracks: List<ListeningRank>,
    val artists: List<ListeningRank>,
)

internal class ListeningTracker(private val record: (ListeningSlice) -> Unit) {
    private data class Sample(val clockMs: Long, val wallMs: Long, val positionMs: Long)
    private data class Run(
        val account: String,
        val loadId: Long,
        val track: PukPlayer.QueueItem,
        val contextUri: String?,
        val session: String = UUID.randomUUID().toString(),
        var sample: Sample,
        var start: Long = sample.wallMs,
        var heard: Long = 0L,
    )

    private var active: Run? = null

    fun sample(
        account: String?, loadId: Long, track: PukPlayer.QueueItem?, contextUri: String?,
        positionMs: Long, clockMs: Long, wallMs: Long, playing: Boolean,
    ) {
        val now = Sample(clockMs, wallMs, positionMs)
        val current = active
        if (current != null && account == current.account && track?.uri == current.track.uri && loadId == current.loadId) {
            val elapsed = now.clockMs - current.sample.clockMs
            val advanced = now.positionMs - current.sample.positionMs
            // A seek is fenced by the listener. Also reject jumps or stale samples defensively.
            if (elapsed in 1..2_000 && advanced in 1..(elapsed + 200)) {
                current.heard += minOf(elapsed, advanced)
            }
            current.sample = now
            if (current.heard >= 5_000 || !playing) flush(current)
            if (!playing) active = null
        } else {
            end()
            if (playing && account != null && track != null) {
                active = Run(account, loadId, track, contextUri, sample = now)
            }
        }
    }

    fun end() {
        active?.let(::flush)
        active = null
    }

    private fun flush(run: Run) {
        if (run.heard > 0) {
            record(ListeningSlice(run.account, run.session, run.track, run.contextUri,
                run.start, run.sample.wallMs.coerceAtLeast(run.start + 1), run.heard))
        }
        run.heard = 0
        run.start = run.sample.wallMs
    }
}
