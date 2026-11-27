package com.project.puk.jam

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import java.util.ArrayDeque
import java.util.Locale

/**
 * Разработческая диагностика качества синхронизации Jam (spec раздел 9).
 * Только logcat (tag «JamDiag»), без UI и без секретов: RTT/jitter, offset,
 * PREPARE→READY, плановый/фактический старт, рассинхрон и коррекции,
 * аудиовыход, состояние приложения. Программная позиция — не акустика:
 * честное акустическое измерение остаётся на общем микрофоне (JAM_TESTING).
 */
object JamDiagnostics {

    @Volatile var appInForeground: Boolean = true; private set
    @Volatile var screenOn: Boolean = true; private set

    // Clock
    @Volatile var lastRttMs: Long = 0; private set
    @Volatile var lastOffsetMs: Long = 0; private set
    private val rttHistory = ArrayDeque<Long>()

    // Start / prepare
    private val prepareDurations = ArrayDeque<Long>()
    @Volatile var lastPlayDeltaMs: Long = 0; private set

    // Drift
    private val gapHistory = ArrayDeque<Long>()
    @Volatile var seeksCount: Long = 0; private set
    @Volatile var nudgesCount: Long = 0; private set
    @Volatile var speedFactor: Double = 1.0; private set
    @Volatile var latencyMs: Long = 0; private set
    @Volatile var audioRoute: String = "SPEAKER"; private set

    private var lifecycleHooked: Application? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var appContext: Context? = null

    fun attach(context: Context) {
        appContext = context.applicationContext
        val app = context.applicationContext as? Application
        if (app != null && lifecycleHooked !== app) {
            lifecycleHooked = app
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                private var resumed = 0
                override fun onActivityResumed(activity: Activity) { resumed++; appInForeground = true }
                override fun onActivityPaused(activity: Activity) { resumed = (resumed - 1).coerceAtLeast(0); if (resumed == 0) appInForeground = false }
                override fun onActivityCreated(a: Activity, s: Bundle?) {}
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
                override fun onActivityDestroyed(a: Activity) {}
            })
        }
        if (screenReceiver == null && appContext != null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    screenOn = intent?.action != Intent.ACTION_SCREEN_OFF
                }
            }
            screenReceiver = receiver
            runCatching {
                appContext!!.registerReceiver(receiver, IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
                })
            }
        }
    }

    fun detach(context: Context) {
        screenReceiver?.let { runCatching { context.applicationContext.unregisterReceiver(it) } }
        screenReceiver = null
    }

    fun noteClockSample(rttMs: Long, offsetMs: Long) {
        lastRttMs = rttMs
        lastOffsetMs = offsetMs
        synchronized(rttHistory) {
            rttHistory.addLast(rttMs)
            while (rttHistory.size > 24) rttHistory.removeFirst()
        }
    }

    fun notePrepareDuration(uri: String, durationMs: Long) {
        synchronized(prepareDurations) {
            prepareDurations.addLast(durationMs)
            while (prepareDurations.size > 16) prepareDurations.removeFirst()
        }
        Log.i("JamDiag", "prepare $uri ready in ${durationMs}ms")
    }

    fun notePlayStart(deltaMs: Long) {
        lastPlayDeltaMs = deltaMs
        Log.i("JamDiag", "play start delta ${deltaMs}ms (фактический − плановый)")
    }

    fun noteGap(gapMs: Long) {
        synchronized(gapHistory) {
            gapHistory.addLast(gapMs)
            while (gapHistory.size > 120) gapHistory.removeFirst()
        }
    }

    fun noteSeek(gapMs: Long) {
        seeksCount++
        Log.i("JamDiag", "drift seek (gap=${gapMs}ms, total=$seeksCount)")
    }

    fun noteNudge(factor: Double) {
        speedFactor = factor
        nudgesCount++
    }

    fun noteResetSpeed() {
        speedFactor = 1.0
    }

    fun noteLatency(ms: Long, route: String) {
        latencyMs = ms
        audioRoute = route
    }

    private fun percentile(values: List<Long>, fraction: Double): Long =
        values.sorted().getOrNull(((values.size - 1) * fraction).toInt().coerceAtLeast(0)) ?: 0

    /** Компактная сводка раз в 10 с; join median/p95 по RTT и по гэпам. */
    fun logSummary(context: String) {
        val rtts = synchronized(rttHistory) { rttHistory.toList() }
        val gaps = synchronized(gapHistory) { gapHistory.toList() }
        val preps = synchronized(prepareDurations) { prepareDurations.toList() }
        val jitter = if (rtts.size >= 4) percentile(rtts, 0.95) - rtts.min() else 0L
        Log.i(
            "JamDiag",
            String.format(
                Locale.US,
                "%s rtt=%d jitterP95=%d offset=%d | gapMed=%d gapP95=%d gapMax=%d seeks=%d nudges=%d speed=%.3f lat=%d(%s) prep=%s | fg=%b screen=%b",
                context,
                lastRttMs, jitter, lastOffsetMs,
                percentile(gaps, 0.5), percentile(gaps, 0.95), gaps.maxOrNull() ?: 0,
                seeksCount, nudgesCount, speedFactor, latencyMs, audioRoute,
                preps.takeIf { it.isNotEmpty() }?.joinToString("/") { "$it" } ?: "-",
                appInForeground, screenOn,
            ),
        )
    }
}
