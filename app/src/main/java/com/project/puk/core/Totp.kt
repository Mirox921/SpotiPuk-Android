package com.project.puk.core

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The rotating TOTP challenge the web player attaches to /api/token requests.
 * The algorithm and its embedded seed are public knowledge (they ship inside
 * the public web player bundle), re-derived here from scratch.
 */
object Totp {

    private const val VERSION = 61
    private const val PERIOD_SECONDS = 30
    private const val MODULUS = 1_000_000
    private const val SEED = ",7/*F(\"rLJ2oxaKL^f+E1xvP@N"

    fun version(): Int = VERSION

    /** Six digit code for the current wall-clock window. */
    fun code(millis: Long): String = codeFor(counterOf(millis))

    /** Counter in 30 second windows, the same value the server recomputes. */
    fun counterOf(millis: Long): Long = millis / 1000L / PERIOD_SECONDS

    private fun codeFor(counter: Long): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(derivedSeedBytes(), "HmacSHA1"))
        val digest = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = digest[digest.size - 1].toInt() and 0x0F
        val binary = ((digest[offset].toInt() and 0x7F) shl 24) or
            ((digest[offset + 1].toInt() and 0xFF) shl 16) or
            ((digest[offset + 2].toInt() and 0xFF) shl 8) or
            (digest[offset + 3].toInt() and 0xFF)
        return (binary % MODULUS).toString().padStart(6, '0')
    }

    /** Expands the seed string into the HMAC key exactly as the web player does. */
    private fun derivedSeedBytes(): ByteArray = SEED
        .mapIndexed { index, ch -> (ch.code xor (index % 33 + 9)).toString() }
        .joinToString("")
        .toByteArray(Charsets.UTF_8)
}
