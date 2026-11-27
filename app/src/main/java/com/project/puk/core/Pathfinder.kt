package com.project.puk.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Speaks Spotify's persisted-query GraphQL dialect (Pathfinder). The sha256
 * hash of each operation comes from three sources, best first:
 *   1. scraped from the public web player JS bundles (always fresh)
 *   2. captured from real web player traffic during login
 *   3. built-in seed constants for the operations MVP needs on day one
 */
object Pathfinder {

    private const val ENDPOINT_GET = "https://api-partner.spotify.com/pathfinder/v1/query"
    private const val ENDPOINT_POST = "https://api-partner.spotify.com/pathfinder/v2/query"
    private const val PREFS_KEY_OPS = "pathfinder_ops"

    /** Fresh scrape wins over a captured one once it is newer. */
    private val scrapeLock = Mutex()
    @Volatile private var scrapeTimestamp: Long = 0L
    @Volatile private var scrapeTable: Map<String, String> = emptyMap()

    private val ops = LinkedHashMap<String, Op>()
    private val opsLock = Any()
    private var loaded = false

    data class Op(val name: String, val hash: String, val vars: String, val capturedAt: Long)

    /** Built-in hashes harvested from public web player bundles; enough to bootstrap. */
    private val SEEDS = mapOf(
        "changeAttributes" to "c51f514c143b5a4ff830bb2e85c3bef7441814ae4e3392a671f476c903c487ac",
        "searchSuggestions" to "f244254b94c0e824d458ac216e0f8406a73f2f69a9ff52831a2f78fa774ff6ce",
        "canvas" to "575138ab27cd5c1b3e54da54d0a7cc8d85485402de26340c2145f0f6bb5e7a9f",
        "pinLibraryItem" to "896ebcb47815681340860d121cb5d494e157e2a78d3950385cd54e0393c67148",
        "unpinLibraryItem" to "896ebcb47815681340860d121cb5d494e157e2a78d3950385cd54e0393c67148",
        "searchTracks" to "b02683192a98dde7966b5e6655a79eeb62713eab703eda9902c932818dd52751",
        "searchArtists" to "7bf95d754fdbe32c8b161fbbe54d1ae50974900df4dce4c8f1afcbcad153224d",
        "searchAlbums" to "202cb3305e31e5a0767ba7925f28bd728cf8f8b0217e6da43909056071cd70e9",
        "searchPlaylists" to "d520014e748f9ea44f7707d8df1819867ac1205e8b7f3e28f22fe5fc858921b1",
        "searchTopResultsList" to "dd78eaff943eba629ed70ee25517b9cea0dcaa41193e2592ae2727660b21892c",
        "home" to "5fb7da7a03ee2776c0670856609cb85a189a3cecc36cc160b91cde79063b9cf1",
        "libraryV3" to "390c78e5b951029bad359785e69b07b536a509c581cbcd0aded5e5067f187455",
        "fetchLibraryTracks" to "087278b20b743578a6262c2b0b4bcd20d879c503cc359a2285baf083ef944240",
        "fetchPlaylist" to "21ea11a6ac75a5f3e01fd365d29c18e0a4a8b7dc09f89bbb5d73910190c6acaf",
        "getAlbum" to "0b6ce3511e1b02e4a40fa95440f992acbe85080eb308af9194322d708fc1914f",
        "queryAlbumTracks" to "0b6ce3511e1b02e4a40fa95440f992acbe85080eb308af9194322d708fc1914f",
        "queryArtistOverview" to "9f8134ef565e78621f1e1793555bd6633c5ac144ae0f89604ed3ae3f80b3c8e6",
        "queryArtistDiscographyAll" to "5e07d323febb57b4a56a42abbf781490e58764aa45feb6e3dc0591564fc56599",
        "queryTrackCreditsGroupedModal" to "f135fb9be58a72d041ab5d214d817021a272405d883860468e2627afb01a3ca9",
        "queryShowMetadataV2" to "b475447846f37cc426add800b995a7859c169c53c72b62de6338c0c40a5dacea",
        "queryPodcastEpisodes" to "3539d746cf882f3909660de40b4ef472b3f5893a0761d617c282541de5d412c5",
        "getEpisodeOrChapter" to "5f77db47e5a2ce6680330bb61317aa09e17ddd1d189513cb218e7cd3e43288be",
        "searchPodcasts" to "0195d9f61b43606d490bca64c3456e3593528cea6cc05c7e822c7c42beed0f4e",
        "searchFullEpisodes" to "397a4e93a21d140d088cfd26641133c5146a2f5f08e420654868153e720f5571",
        "accountAttributes" to "be4ce359daee01463ddb8e77536220e108e3d28105e12199f244d7a5954e9d14",
        "profileAttributes" to "08ffb4730af3746e04a8301396f20875dbbce10c75243803091a9274eacc8ac0",
        "decorateContextTracks" to "383de00240775c39a6afe0b1055dc562b2a3930894201f9762f3fc32a74971c7",
        "areEntitiesInLibrary" to "134337999233cc6fdd6b1e6dbf94841409f04a946c5c7b744b09ba0dfe5a85ed",
        "editablePlaylists" to "d5c4b8096437dcc2ac9528c91dfcd299e35b747cda2f8f75d28f41f49c5092ba",
        "addToPlaylist" to "b907ad6b088d5bf7680fa81198996d58398c2d55dfcdf59fcc959e0be98a418a",
        "addToLibrary" to "896ebcb47815681340860d121cb5d494e157e2a78d3950385cd54e0393c67148",
        "removeFromLibrary" to "896ebcb47815681340860d121cb5d494e157e2a78d3950385cd54e0393c67148",
    )

    private class Session {
        val id: String = java.util.UUID.randomUUID().toString()
    }
    private val session = Session()
    fun sessionToken(): String = session.id

    // ---- op store ----

    fun record(context: Context, name: String, hash: String, vars: String) {
        if (name.isBlank() || hash.isBlank()) return
        synchronized(opsLock) {
            load(context)
            ops[name] = Op(name, hash, vars, System.currentTimeMillis())
            persist(context)
        }
        Log.d("Pathfinder", "captured op $name")
    }

    fun hashOf(context: Context, name: String): String? {
        synchronized(opsLock) {
            load(context)
            val captured = ops[name]
        }
        val scraped = scrapeTable[name]
        return scraped ?: synchronized(opsLock) { ops[name]?.hash } ?: SEEDS[name]
    }

    fun variablesOf(context: Context, name: String): String = synchronized(opsLock) {
        load(context)
        ops[name]?.vars.orEmpty()
    }

    fun capturedCount(context: Context): Int = synchronized(opsLock) {
        load(context)
        ops.size
    }

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = SecurePrefs.prefs(context).getString(PREFS_KEY_OPS, null) ?: return
        runCatching {
            val root = JSONObject(raw)
            root.keys().asSequence().forEach { name ->
                val node = root.optJSONObject(name) ?: return@forEach
                val hash = node.optString("hash")
                if (hash.isNotBlank()) {
                    ops[name] = Op(name, hash, node.optString("vars"), node.optLong("at", 0L))
                }
            }
            Log.i("Pathfinder", "loaded ${ops.size} captured ops")
        }
    }

    private fun persist(context: Context) {
        val root = JSONObject()
        ops.values.forEach { op ->
            root.put(op.name, JSONObject().put("hash", op.hash).put("vars", op.vars).put("at", op.capturedAt))
        }
        SecurePrefs.edit(context) { it.putString(PREFS_KEY_OPS, root.toString()) }
    }

    // ---- request building ----

    private fun headersFor(context: Context, token: String): Map<String, String> = buildMap {
        put("Authorization", "Bearer $token")
        put("Accept", "application/json")
        put("app-platform", "WebPlayer")
        put("Origin", "https://open.spotify.com")
        put("Referer", "https://open.spotify.com/")
        put("User-Agent", Net.DESKTOP_UA)
        val clientToken = Auth.clientToken(context)
        if (clientToken.isNotBlank()) put("client-token", clientToken)
    }

    private fun bodyOf(operation: String, hash: String, variables: String): String = JSONObject()
        .put("variables", JSONObject(variables.ifBlank { "{}" }))
        .put("operationName", operation)
        .put("extensions", JSONObject().put("persistedQuery", JSONObject().put("version", 1).put("sha256Hash", hash)))
        .toString()

    private fun endpointUrl(operation: String, hash: String, variables: String): String =
        ENDPOINT_GET +
            "?operationName=" + URLEncoder.encode(operation, "UTF-8") +
            "&variables=" + URLEncoder.encode(variables.ifBlank { "{}" }, "UTF-8") +
            "&extensions=" + URLEncoder.encode(
                """{"persistedQuery":{"version":1,"sha256Hash":"$hash"}}""",
                "UTF-8",
            )

    private fun isStaleHash(response: Net.Response): Boolean {
        if (response.code != 200 && response.code != 400) return false
        return response.body.contains("PersistedQueryNotFound", true) ||
            response.body.contains("PersistedQueryNotSupported", true)
    }

    // ---- queries ----

    suspend fun queryPost(
        context: Context,
        token: String,
        operation: String,
        variables: String = "{}",
        fallbackToGet: Boolean = true,
    ): JSONObject? {
        var hash = hashOf(context, operation)
        if (hash == null) {
            Log.w("Pathfinder", "no hash for $operation")
            return null
        }
        var response = Net.postJson(ENDPOINT_POST, bodyOf(operation, hash ?: hashOf(context, operation).orEmpty(), variables), headersFor(context, token), "pf/$operation")
        if (response != null && isStaleHash(response)) {
            Log.w("Pathfinder", "$operation hash rejected, rescraping")
            refreshHashes(context)
            hash = hashOf(context, operation)
            if (hash != null) {
                response = Net.postJson(ENDPOINT_POST, bodyOf(operation, hash ?: hashOf(context, operation).orEmpty(), variables), headersFor(context, token), "pf/$operation")
            }
        }
        val finished = finish(context, token, operation, hash, variables, response) ?: return null
        return finished
    }

    suspend fun query(
        context: Context,
        token: String,
        operation: String,
        variables: String = "{}",
    ): JSONObject? {
        var hash = hashOf(context, operation)
        if (hash == null) {
            Log.w("Pathfinder", "no hash for $operation")
            return null
        }
        var response = Net.get(endpointUrl(operation, hash, variables), headersFor(context, token), "pf/$operation")
        if (response != null && isStaleHash(response)) {
            Log.w("Pathfinder", "$operation hash rejected, rescraping")
            refreshHashes(context)
            hash = hashOf(context, operation)
            if (hash != null) {
                response = Net.get(endpointUrl(operation, hash, variables), headersFor(context, token), "pf/$operation")
            }
        }
        return finish(context, token, operation, hash, variables, response)
    }

    /** 401/403/429 handling: refresh the bearer first, then the client token; honour Retry-After.
     *  Retries reuse the [variables]/[hash] of the original call: re-capturing them from the
     *  login-time op table sent stale pages for per-page operations (library pagination). */
    private suspend fun finish(
        context: Context,
        token: String,
        operation: String,
        hash: String?,
        variables: String,
        response: Net.Response?,
    ): JSONObject? {
        if (response == null) return null
        if (response.code == 401 || response.code == 403) {
            // The bearer usually expired: refresh it and retry with the new
            // token; the client token is reminted only if still rejected.
            Log.w("Pathfinder", "$operation http=${response.code}, refreshing bearer")
            Auth.invalidateToken(context)
            val fresh = TokenMinter.refresh(context)
            if (fresh.isNullOrBlank()) return null
            val retry = Net.postJson(
                ENDPOINT_POST,
                bodyOf(operation, hash ?: hashOf(context, operation).orEmpty(), variables),
                headersFor(context, fresh),
                "pf/$operation",
            )
            if (retry != null && retry.ok) return retry.json()
            if (retry != null && (retry.code == 401 || retry.code == 403)) {
                Log.w("Pathfinder", "$operation still ${retry.code}, reminting client-token")
                ClientTokenManager.get(context, force = true)
                if (Auth.clientToken(context).isNotBlank()) {
                    val last = Net.postJson(
                        ENDPOINT_POST,
                        bodyOf(operation, hash ?: hashOf(context, operation).orEmpty(), variables),
                        headersFor(context, fresh),
                        "pf/$operation",
                    )
                    if (last != null && last.ok) return last.json()
                }
            }
            return null
        }
        if (response.code == 429) {
            // Rate limited: wait out the capped Retry-After and retry once
            // instead of hammering the endpoint (and worsening the limit).
            val wait = response.retryAfterSeconds().coerceIn(1L, 30L)
            Log.w("Pathfinder", "$operation rate limited, backing off ${wait}s")
            delay(wait * 1000)
            val retry = Net.postJson(
                ENDPOINT_POST,
                bodyOf(operation, hash ?: hashOf(context, operation).orEmpty(), variables),
                headersFor(context, token),
                "pf/$operation",
            )
            if (retry != null && retry.ok) return retry.json()
            Log.w("Pathfinder", "$operation http=${retry?.code ?: "no response"} after backoff")
            return null
        }
        if (!response.ok) {
            Log.w("Pathfinder", "$operation http=${response.code} ${response.body.take(160)}")
            return null
        }
        return withContext(Dispatchers.Default) { response.json() }
    }

    suspend fun refreshHashes(context: Context) = scrapeLock.withLock {
        val table = BundleScraper.scrape()
        if (table.isNotEmpty()) {
            scrapeTable = table
            scrapeTimestamp = System.currentTimeMillis()
            Log.i("Pathfinder", "bundle scrape -> ${table.size} hashes")
        }
    }

    fun scrapeAgeMs(): Long = if (scrapeTimestamp == 0L) -1L else System.currentTimeMillis() - scrapeTimestamp
}
