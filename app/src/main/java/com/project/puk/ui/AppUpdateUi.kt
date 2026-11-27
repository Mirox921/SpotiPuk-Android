package com.project.puk.ui

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.project.puk.core.AppUpdater
import com.project.puk.core.Lang
import com.project.puk.core.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Global pending-update state + the update dialog + the drawer «Обновления»
 * screen, hosted inside AppRoot so the material3 dialogs inherit the Ember
 * theme. core/AppUpdater.kt decides WHEN; this file only renders and runs the
 * download/install flow. The flow stays in-app: DownloadManager fetches the
 * APK, the system installer opens on top of the app, and ACTION_PACKAGE_
 * REPLACED re-opens the freshly updated app (no manual restart needed).
 */
object AppUpdateUi {
    var pending by mutableStateOf<AppUpdater.Info?>(null)
        private set

    /** Drawer badge: a newer version exists (set by the background check). */
    var updateAvailable by mutableStateOf(false)
        private set

    /** Updates-screen state: manual check + download progress. */
    var checking by mutableStateOf(false)
        private set
    var screenResult by mutableStateOf<AppUpdater.CheckResult?>(null)
        private set
    var downloadingId by mutableStateOf<Long?>(null)
        private set
    var progress by mutableStateOf<AppUpdater.DownloadState?>(null)
        private set
    var installing by mutableStateOf(false)
        private set

    fun offer(info: AppUpdater.Info) {
        pending = info
        updateAvailable = true
        screenResult = AppUpdater.CheckResult.Newer(info)
    }

    fun dismiss() {
        pending = null
    }

    suspend fun check(context: Context) {
        checking = true
        screenResult = try {
            AppUpdater.check(context).also {
                if (it is AppUpdater.CheckResult.Newer) updateAvailable = true
            }
        } finally {
            checking = false
        }
    }

    fun startDownload(context: Context, info: AppUpdater.Info) {
        progress = null
        installing = false
        downloadingId = runCatching { AppUpdater.enqueueDownload(context, info) }.getOrNull()
    }

    fun markDownloaded(id: Long) {
        if (downloadingId == id) {
            downloadingId = null
            progress = null
        }
    }

    /** Progress updates from the polling loop (setters are object-private). */
    internal fun setProgress(id: Long, st: AppUpdater.DownloadState?) {
        if (downloadingId == id) progress = st
    }

    internal fun markInstalling() {
        installing = true
    }

    internal fun markNotInstalling() {
        installing = false
    }
}

/** Language picker (Язык / Language): sets Lang and lets composition re-read t(). */
object AppLangUi {
    var visible by mutableStateOf(false)
        private set

    fun show() {
        visible = true
    }

    fun dismiss() {
        visible = false
    }
}

@Composable
fun AppLangDialog() {
    if (!AppLangUi.visible) return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { AppLangUi.dismiss() },
        title = { Text(Lang.t(Lang.Key.LANGUAGE_SECTION)) },
        text = {
            Column {
                Lang.Code.entries.forEach { code ->
                    val selected = Lang.current == code
                    TextButton(onClick = {
                        val previous = Lang.current
                        Lang.set(context, code)
                        if (previous != code) {
                            val playback = com.project.puk.player.PlaybackService.engine?.state?.value
                            com.project.puk.player.PlaybackWidget.render(context, playback)
                        }
                        AppLangUi.dismiss()
                    }) {
                        Text(if (selected) "✓ " + code.label else code.label)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { AppLangUi.dismiss() }) { Text(Lang.t(Lang.Key.SHEET_CLOSE)) }
        },
    )
}

private const val DOWNLOAD_COMPLETE_ACTION = DownloadManager.ACTION_DOWNLOAD_COMPLETE
private const val TAG = "AppUpdate"

@Composable
fun AppUpdateHost() {
    val context = LocalContext.current

    // DownloadManager completion → open the system installer on top of the
    // app; PACKAGE_REPLACED (our package) → re-open the updated app so the
    // whole update runs without leaving SpotiPuk. The receiver is EXPORTED:
    // DownloadManager lives outside this app, so NOT_EXPORTED drops the
    // completion broadcast and the UI spinner would spin forever.
    LaunchedEffect(Unit) {
        val downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                val expected = AppUpdateUi.downloadingId ?: return
                if (id != expected) return
                AppUpdateUi.markDownloaded(id)
                val ok = AppUpdater.openInstaller(ctx, id)
                if (!ok) {
                    Log.e(TAG, "installer did not open for download id=$id")
                    android.widget.Toast
                        .makeText(ctx, Lang.t(Lang.Key.UPDATES_APK_READY), android.widget.Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
        ContextCompat.registerReceiver(
            context.applicationContext,
            downloadReceiver,
            IntentFilter(DOWNLOAD_COMPLETE_ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )

        val replaceReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.data?.schemeSpecificPart != ctx.packageName) return
                ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { launch ->
                    launch.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                    )
                    runCatching { ctx.startActivity(launch) }
                }
            }
        }
        ContextCompat.registerReceiver(
            context.applicationContext,
            replaceReceiver,
            IntentFilter(Intent.ACTION_PACKAGE_REPLACED).apply {
                addDataScheme("package")
                addDataSchemeSpecificPart(context.packageName, android.os.PatternMatcher.PATTERN_LITERAL)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    val info = AppUpdateUi.pending ?: return
    AlertDialog(
        onDismissRequest = { AppUpdateUi.dismiss() },
        title = { Text(Lang.t(Lang.Key.UPDATE_DIALOG_TITLE)) },
        text = {
            Column {
                Text(
                    "SpotiPuk ${info.versionName} (vc${info.versionCode})",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (info.notes.isNotEmpty()) {
                    Text(
                        info.notes.joinToString(separator = "\n") { "• $it" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                AppUpdateUi.startDownload(context, info)
                if (AppUpdateUi.downloadingId == null) {
                    android.widget.Toast
                        .makeText(context, Lang.t(Lang.Key.UPDATES_OPEN_ERROR), android.widget.Toast.LENGTH_SHORT)
                        .show()
                }
                AppUpdateUi.dismiss()
            }) { Text(Lang.t(Lang.Key.DOWNLOAD_APK)) }
        },
        dismissButton = {
            TextButton(onClick = { AppUpdateUi.dismiss() }) { Text(Lang.t(Lang.Key.LATER)) }
        },
    )
}

/** Drawer → «Обновления»: version status, changelog of the pending release, in-app update. */
@Composable
fun UpdatesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        ProfilePageHeader(Lang.t(Lang.Key.UPDATES_TITLE), onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(Lang.t(Lang.Key.UPDATES_WHATS_NEW), style = MaterialTheme.typography.headlineSmall)
            @Suppress("DEPRECATION")
            val version = remember(context) {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }
            Text(
                Lang.t(Lang.Key.UPDATES_CURRENT) + if (!version.isNullOrBlank()) " — $version" else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                AppUpdateUi.checking -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp))
                        Text(
                            Lang.t(Lang.Key.UPDATES_CHECKING),
                            Modifier.padding(start = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> OutlinedButton(onClick = { scope.launch { AppUpdateUi.check(context) } }) {
                    Text(Lang.t(Lang.Key.UPDATES_CHECK_NOW))
                }
            }

            // Live DownloadManager progress; polling is the reliable completion
            // path (the broadcast can be missed by an EXPORTED/NOT_EXPORTED race).
            LaunchedEffect(AppUpdateUi.downloadingId) {
                val id = AppUpdateUi.downloadingId ?: return@LaunchedEffect
                while (AppUpdateUi.downloadingId == id) {
                    val st = AppUpdater.downloadState(context, id)
                    AppUpdateUi.setProgress(id, st)
                    when {
                        st != null && st.status == DownloadManager.STATUS_SUCCESSFUL -> {
                            AppUpdateUi.markDownloaded(id)
                            AppUpdateUi.markInstalling()
                            val ok = AppUpdater.openInstaller(context, id)
                            if (!ok) {
                                AppUpdateUi.markNotInstalling()
                                Log.e(TAG, "installer did not open for download id=$id")
                                android.widget.Toast
                                    .makeText(context, Lang.t(Lang.Key.UPDATES_APK_READY), android.widget.Toast.LENGTH_LONG)
                                    .show()
                            }
                            break
                        }
                        st != null && st.status == DownloadManager.STATUS_FAILED -> {
                            AppUpdateUi.markDownloaded(id)
                            android.widget.Toast
                                .makeText(context, Lang.t(Lang.Key.UPDATES_DOWNLOAD_FAILED), android.widget.Toast.LENGTH_LONG)
                                .show()
                            break
                        }
                    }
                    delay(700)
                }
            }

            val result = AppUpdateUi.screenResult
            when (result) {
                is AppUpdater.CheckResult.Newer -> UpdateAvailableCard(result.info)
                AppUpdater.CheckResult.UpToDate -> Text(
                    Lang.t(Lang.Key.UPDATES_LATEST),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is AppUpdater.CheckResult.Failed -> Text(
                    result.message,
                    color = MaterialTheme.colorScheme.error,
                )
                null -> Unit
            }

            // Full change history as text (site/app/history.json), newest first.
            var history by remember { mutableStateOf<List<AppUpdater.HistoryEntry>?>(null) }
            LaunchedEffect(Unit) { history = AppUpdater.history() }
            Text(Lang.t(Lang.Key.UPDATES_HISTORY), style = MaterialTheme.typography.titleLarge)
            val hist = history
            when {
                hist == null -> CircularProgressIndicator(Modifier.size(20.dp))
                hist.isEmpty() -> Text(
                    Lang.t(Lang.Key.UPDATES_NO_ENTRIES),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> hist.forEach { entry ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "SpotiPuk ${entry.versionName} (vc${entry.versionCode})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        if (entry.date.isNotBlank()) Text(
                            entry.date,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        entry.notes.forEach { note ->
                            Text(
                                "• $note",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Text(
                Lang.t(Lang.Key.CHANGELOG_HINT),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UpdateAvailableCard(info: AppUpdater.Info) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(Lang.t(Lang.Key.UPDATES_NEW_CARD), style = MaterialTheme.typography.titleMedium)
            Text(
                "SpotiPuk ${info.versionName} (vc${info.versionCode})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            if (info.notes.isNotEmpty()) {
                Text(Lang.t(Lang.Key.UPDATES_CHANGELOG_LABEL), style = MaterialTheme.typography.labelLarge)
                Text(
                    info.notes.joinToString(separator = "\n") { "• $it" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (AppUpdateUi.installing) {
                Text(
                    Lang.t(Lang.Key.UPDATES_INSTALLING),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (AppUpdateUi.downloadingId != null) {
                val pr = AppUpdateUi.progress
                val pct = if (pr != null && pr.total > 0) (pr.bytes * 100 / pr.total).toInt() else null
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text(
                        Lang.t(Lang.Key.UPDATES_DOWNLOADING) + (pct?.let { " $it%" } ?: ""),
                        Modifier.padding(start = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(
                    progress = { (pct ?: 0) / 100f },
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            } else {
                Button(onClick = { AppUpdateUi.startDownload(context, info) }) {
                    Text(Lang.t(Lang.Key.UPDATES_INSTALL))
                }
            }
        }
    }
}
