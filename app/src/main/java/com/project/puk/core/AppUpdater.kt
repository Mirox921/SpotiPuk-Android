package com.project.puk.core

import com.project.puk.core.Lang

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import com.project.puk.BuildConfig

/**
 * App self-update against the static version-check JSON on spotipuk.stream
 * (site/app/latest.json → served at /v1/app/latest). Parse/compare are pure
 * and unit-tested; the UI dialog lives in ui/AppUpdateUi.kt.
 */
object AppUpdater {

    private const val TAG = "AppUpdate"

    const val UPDATE_MANIFEST_URL = "https://spotipuk.stream/v1/app/latest"
    const val HISTORY_MANIFEST_URL = "https://spotipuk.stream/v1/app/history"

    data class Info(
        val versionCode: Int,
        val versionName: String,
        val url: String,
        val sha256: String?,
        private val notesRu: List<String>,
        private val notesEn: List<String>?,
    ) {
        val notes: List<String> get() = if (Lang.current == Lang.Code.RU) notesRu else notesEn ?: notesRu
    }

    data class HistoryEntry(
        val versionName: String,
        val versionCode: Int,
        private val dateRu: String,
        private val dateEn: String,
        private val notesRu: List<String>,
        private val notesEn: List<String>?,
    ) {
        val date: String get() = if (Lang.current == Lang.Code.RU) dateRu else dateEn.ifBlank { dateRu }
        val notes: List<String> get() = if (Lang.current == Lang.Code.RU) notesRu else notesEn ?: notesRu
    }

    sealed interface CheckResult {
        data class Newer(val info: Info) : CheckResult
        data object UpToDate : CheckResult
        data class Failed(val messageKey: Lang.Key) : CheckResult {
            val message: String get() = Lang.t(messageKey)
        }
    }

    /** Pure parse: malformed JSON or missing versionCode/url → null. */
    fun parse(text: String): Info? = runCatching {
        val o = org.json.JSONObject(text)
        val code = o.optInt("versionCode", 0)
        val url = o.optString("url", "").trim()
        if (code <= 0 || url.isEmpty()) return null
        Info(
            versionCode = code,
            versionName = o.optString("versionName", "").ifBlank { "…" },
            url = url,
            sha256 = o.optString("sha256", "").trim().ifEmpty { null },
            notesRu = o.notes("changelog").orEmpty(),
            notesEn = o.notes("changelogEn"),
        )
    }.getOrNull()

    private fun org.json.JSONObject.notes(key: String): List<String>? = optJSONArray(key)?.let { entries ->
        List(entries.length()) { entries.optString(it, "") }.filter(String::isNotBlank)
    }

    fun isNewer(info: Info?, currentVersionCode: Int): Boolean =
        info != null && info.versionCode > currentVersionCode

    /** Network check; never throws — network/parse failures are a Failed result. */
    suspend fun check(context: Context): CheckResult {
        // Compare against the INSTALLED version, not the running binary: right
        // after an in-app update the old process still reports the old
        // BuildConfig, which would re-offer the same update forever.
        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        }.getOrDefault(BuildConfig.VERSION_CODE)
        val response = runCatching { Net.get(UPDATE_MANIFEST_URL, tag = TAG) }.getOrNull()
            ?: return CheckResult.Failed(Lang.Key.UPDATES_SERVER_UNAVAILABLE)
        if (!response.ok) return CheckResult.Failed(Lang.Key.UPDATES_SERVER_UNAVAILABLE)
        val info = parse(response.body)
            ?: return CheckResult.Failed(Lang.Key.UPDATES_BAD_RESPONSE)
        return if (isNewer(info, installed)) {
            CheckResult.Newer(info)
        } else {
            CheckResult.UpToDate
        }
    }

    /**
     * Update history for the in-app «Обновления» screen. The manifest carries
     * RU notes plus EN notes (notesEn) — non-RU locales read the EN variant
     * when present. Never throws; failures render as an empty history.
     */
    suspend fun history(): List<HistoryEntry> {
        val response = runCatching { Net.get(HISTORY_MANIFEST_URL, tag = TAG) }.getOrNull() ?: return emptyList()
        if (!response.ok) return emptyList()
        return runCatching {
            val arr = org.json.JSONObject(response.body).optJSONArray("releases") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    add(
                        HistoryEntry(
                            versionName = o.optString("versionName", ""),
                            versionCode = o.optInt("versionCode", 0),
                            dateRu = o.optString("date"),
                            dateEn = o.optString("dateEn"),
                            notesRu = o.notes("notes").orEmpty(),
                            notesEn = o.notes("notesEn"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Enqueues the APK into DownloadManager (visible in the notification
     * shade) and returns its id. Installation is offered on completion —
     * see ui/AppUpdateUi.kt.
     */
    fun enqueueDownload(context: Context, info: Info): Long {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val fileName = "SpotiPuk-${info.versionName}-vc${info.versionCode}.apk"
        val request = DownloadManager.Request(Uri.parse(info.url))
            .setTitle("SpotiPuk ${info.versionName}")
            .setDescription(Lang.t(Lang.Key.UPDATE_DOWNLOAD_DESC))
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        return manager.enqueue(request)
    }

    data class DownloadState(val status: Int, val bytes: Long, val total: Long)

    /** Live DownloadManager state for the progress UI; null when the row is gone. */
    fun downloadState(context: Context, id: Long): DownloadState? = runCatching {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            DownloadState(
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
            )
        }
    }.getOrNull()

    /**
     * Fires the system package installer for a finished download.
     * Requires REQUEST_INSTALL_PACKAGES (declared in the manifest); Android
     * asks the user to allow installs from this app on first use.
     */
    fun openInstaller(context: Context, downloadId: Long): Boolean {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = runCatching { manager.getUriForDownloadedFile(downloadId) }.getOrNull()
            ?: return false
        val status = runCatching {
            manager.query(
                DownloadManager.Query().setFilterById(downloadId).setFilterByStatus(DownloadManager.STATUS_SUCCESSFUL),
            ).use { cursor ->
                if (!cursor.moveToFirst()) -1
                else cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            }
        }.getOrDefault(-1)
        if (status != DownloadManager.STATUS_SUCCESSFUL) return false
        val install = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK,
            )
        return runCatching { context.startActivity(install) }.isSuccess
    }
}
