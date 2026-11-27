package com.project.puk.core

import android.content.Context
import org.json.JSONObject

/**
 * Scrapes persisted-query hashes straight out of the public web player
 * JavaScript on open.spotifycdn.com. No WebView and no login required,
 * so stale hashes can always be repaired in the background.
 */
object BundleScraper {

    private const val SHELL_URL = "https://open.spotify.com/"
    private const val CDN_ROOT = "https://open.spotifycdn.com/cdn/build/web-player/"

    /** ("operationName","query|mutation","64 hex chars") triples inside the bundles. */
    private val triple = Regex("\\(\"([A-Za-z][A-Za-z0-9_]*)\",\"(?:query|mutation)\",\"([0-9a-f]{64})\"")

    /** Bundle URLs listed by the app shell. */
    private val bundleUrl = Regex("https://open\\.spotifycdn\\.com/cdn/build/web-player/[A-Za-z0-9~._-]+\\.js")

    private const val MAX_BUNDLES = 10

    suspend fun scrape(): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        val shell = Net.get(SHELL_URL, mapOf("User-Agent" to Net.DESKTOP_UA), "hash-shell")?.body
        if (shell.isNullOrBlank()) {
            Log.w("Scraper", "no app shell")
            return found
        }
        val bundles = bundleUrl.findAll(shell)
            .map { it.value }
            .distinct()
            .toList()
        Log.i("Scraper", "shell lists ${bundles.size} bundles")
        for (url in bundles.take(MAX_BUNDLES)) {
            val body = Net.get(url, mapOf("User-Agent" to Net.DESKTOP_UA), "hash-bundle")?.body ?: continue
            triple.findAll(body).forEach { match ->
                val name = match.groupValues[1]
                if (!found.containsKey(name)) found[name] = match.groupValues[2]
            }
            if (found.size > 120) break
        }
        if (found.isEmpty()) Log.w("Scraper", "scrape found no hashes")
        return found
    }

    /** Persists a scrape result into prefs so it survives restarts. */
    fun persist(context: Context, table: Map<String, String>) {
        val root = JSONObject()
        table.forEach { (name, hash) -> root.put(name, hash) }
        AppPrefs.prefs(context).edit()
            .putString("bundle_hashes", root.toString())
            .putLong("bundle_hashes_at", System.currentTimeMillis())
            .apply()
    }

    fun loadPersisted(context: Context): Pair<Map<String, String>, Long> {
        val prefs = AppPrefs.prefs(context)
        val raw = prefs.getString("bundle_hashes", null) ?: return emptyMap<String, String>() to 0L
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap<String, String>() to 0L
        val table = root.keys().asSequence().associateWith { root.optString(it) }
        return table to prefs.getLong("bundle_hashes_at", 0L)
    }
}
