package com.project.puk.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.security.SecureRandom
import java.util.UUID

/**
 * Encrypted storage for everything sensitive: access/client tokens, the
 * cookie session snapshot and the account identity. Falls back to regular
 * prefs if the master key is unavailable rather than crashing the app.
 */
object SecurePrefs {

    private const val FILE = "spotipuk_secure"
    private const val FALLBACK_FILE = "spotipuk_fallback"

    @Volatile
    private var prefsRef: SharedPreferences? = null

    @Volatile
    private var useFallback = false

    fun prefs(context: Context): SharedPreferences {
        prefsRef?.let { return it }
        synchronized(this) {
            prefsRef?.let { return it }
            val created = try {
                create(context)
            } catch (e: Throwable) {
                Log.e("Prefs", "encrypted prefs unavailable (${e.javaClass.simpleName}), plain fallback", e)
                useFallback = true
                context.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
            }
            prefsRef = created
            // EncryptedSharedPreferences can also throw mid-session (corrupt
            // KeyStore tag on read/write); every call is guarded and retried
            // against plain prefs instead of crashing.
            return hardened(created, context)
        }
    }

    private fun fallback(context: Context): SharedPreferences =
        context.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)

    private fun demoteToPlain(context: Context) {
        synchronized(this) {
            if (!useFallback) {
                useFallback = true
                prefsRef = null
            }
        }
    }

    private fun hardened(raw: SharedPreferences, context: Context): SharedPreferences =
        Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
        ) { _, method, args ->
            val argv = args ?: emptyArray<Any?>()
            try {
                val result = method.invoke(raw, *argv)
                if (method.name == "edit") hardenedEditor(result as SharedPreferences.Editor, context) else result
            } catch (failure: InvocationTargetException) {
                Log.e("Prefs", "encrypted prefs ${method.name} failed (${failure.cause?.javaClass?.simpleName}), plain fallback", failure.cause)
                demoteToPlain(context)
                val plain = fallback(context)
                if (method.name == "edit") plain.edit() else method.invoke(plain, *argv)
            }
        } as SharedPreferences

    private fun hardenedEditor(raw: SharedPreferences.Editor, context: Context): SharedPreferences.Editor =
        Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            val argv = args ?: emptyArray<Any?>()
            try {
                method.invoke(raw, *argv)
            } catch (failure: InvocationTargetException) {
                Log.e("Prefs", "encrypted editor ${method.name} failed (${failure.cause?.javaClass?.simpleName}), plain fallback", failure.cause)
                demoteToPlain(context)
                method.invoke(fallback(context).edit(), *argv)
            }
        } as SharedPreferences.Editor

    private fun create(context: Context): SharedPreferences {
        val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            FILE,
            masterKey,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun edit(context: Context, block: (SharedPreferences.Editor) -> Unit) {
        val editor = prefs(context).edit()
        block(editor)
        editor.apply()
    }
}

/** Plain, non-sensitive preferences (UI settings only). */
object AppPrefs {
    private const val FILE = "spotipuk_prefs2"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

/** Stable per-install device identity used for Spotify Connect. */
object Identity {

    private const val KEY_DEVICE_ID = "device_id"

    fun deviceId(context: Context): String {
        val stored = SecurePrefs.prefs(context).getString(KEY_DEVICE_ID, null)
        if (!stored.isNullOrBlank()) return stored
        val generated = UUID.randomUUID().toString()
        SecurePrefs.edit(context) { it.putString(KEY_DEVICE_ID, generated) }
        Log.i("Identity", "minted device id $generated")
        return generated
    }
}
