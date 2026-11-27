package com.project.puk.core

import org.json.JSONObject

/** Serialized only inside SecurePrefs. No bearer tokens are retained between accounts. */
internal data class SavedAccountSession(val profile: Auth.Account, val cookies: String)

internal object SavedAccounts {
    fun decode(raw: String?): List<SavedAccountSession> = runCatching {
        val root = JSONObject(raw ?: "{}")
        root.keys().asSequence().mapNotNull { id ->
            val item = root.optJSONObject(id) ?: return@mapNotNull null
            val profile = item.optJSONObject("profile") ?: return@mapNotNull null
            val cookies = item.optString("cookies")
            if (id.isBlank() || profile.optString("id") != id || cookies.isBlank()) return@mapNotNull null
            SavedAccountSession(Auth.Account(id, profile.optString("name"), profile.optString("email"),
                profile.optString("image"), profile.optString("product"), profile.optString("country")), cookies)
        }.toList()
    }.getOrDefault(emptyList())

    fun encode(entries: List<SavedAccountSession>): String = JSONObject().apply {
        entries.forEach { entry ->
            val profile = entry.profile
            put(profile.id, JSONObject().put("cookies", entry.cookies).put("profile", JSONObject()
                .put("id", profile.id).put("name", profile.name).put("email", profile.email)
                .put("image", profile.imageUrl).put("product", profile.product).put("country", profile.country)))
        }
    }.toString()

    fun upsert(entries: List<SavedAccountSession>, session: SavedAccountSession) =
        entries.filterNot { it.profile.id == session.profile.id } + session
}
