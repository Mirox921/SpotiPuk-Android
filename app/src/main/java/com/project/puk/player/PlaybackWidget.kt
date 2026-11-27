package com.project.puk.player

import com.project.puk.core.Lang

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.media3.common.util.UnstableApi
import com.project.puk.R
import com.project.puk.connect.Connect
import com.project.puk.core.Auth
import com.project.puk.ui.MainActivity

@UnstableApi
class PlaybackWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, PlaybackService.engine?.state?.value, ids)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action != toggle && action != next || !canControl(context)) return
        val engine = PlaybackService.engine ?: return
        if (engine.current() == null) return
        if (action == toggle) engine.togglePlayPause() else engine.next()
    }

    companion object {
        private const val toggle = "com.project.puk.widget.PLAY_PAUSE"
        private const val next = "com.project.puk.widget.NEXT"
        private const val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        private fun canControl(context: Context): Boolean = Auth.isLoggedIn(context) &&
            Connect.clusterActiveDevice.value.let { it.isBlank() || Connect.isLocalDevice(it) }

        internal fun render(context: Context, state: PukPlayer.State?, ids: IntArray? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val widgets = ids ?: manager.getAppWidgetIds(ComponentName(context, PlaybackWidget::class.java))
            if (widgets.isEmpty()) return
            val track = if (canControl(context)) state?.queue?.getOrNull(state.index) else null
            val playing = track != null && state?.playing == true
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), flags)
            fun action(name: String) = PendingIntent.getBroadcast(context, 0,
                Intent(context, PlaybackWidget::class.java).setAction(name), flags)
            val views = RemoteViews(context.packageName, R.layout.playback_widget).apply {
                setTextViewText(R.id.widget_title, track?.title ?: "SpotiPuk")
                setTextViewText(R.id.widget_artist, track?.artist.orEmpty())
                setImageViewResource(R.id.widget_play, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
                setContentDescription(R.id.widget_play, if (playing) Lang.t(Lang.Key.JAM_PAUSE) else Lang.t(Lang.Key.JAM_PLAY))
                setContentDescription(R.id.widget_next, Lang.t(Lang.Key.JAM_NEXT_TRACK))
                setOnClickPendingIntent(R.id.widget_open, open)
                setOnClickPendingIntent(R.id.widget_play, if (track == null) open else action(toggle))
                setOnClickPendingIntent(R.id.widget_next, if (track == null) open else action(next))
            }
            manager.updateAppWidget(widgets, views)
        }
    }
}
