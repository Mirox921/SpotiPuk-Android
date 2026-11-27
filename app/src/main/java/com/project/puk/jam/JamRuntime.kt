package com.project.puk.jam

import android.content.Context
import com.project.puk.player.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * App-scoped home for the Jam controller. The session must outlive any single
 * Activity composition: audio keeps playing through PlaybackService while the
 * app is backgrounded, and the host still reports TRACK_ENDED from the
 * background (the server decides advancement — correction #3). The controller
 * previously lived in `remember {}`, so its socket died on composition
 * disposal and the session tore down for everyone on swipe-from-recents.
 */
object JamRuntime {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var instance: JamController? = null

    fun controller(context: Context): JamController =
        instance ?: JamController(
            context.applicationContext,
            PlaybackService.engine(context),
            scope,
        ).also { instance = it }
}
