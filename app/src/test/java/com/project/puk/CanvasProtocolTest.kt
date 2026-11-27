package com.project.puk

import com.project.puk.api.CanvasProtocol
import org.junit.Assert.*
import org.junit.Test

class CanvasProtocolTest {
    private fun field(tag: Int, value: String): ByteArray {
        val bytes = value.toByteArray()
        return byteArrayOf(tag.toByte(), bytes.size.toByte()) + bytes
    }

    @Test fun requestWrapsEntityUriInRepeatedEntityField() {
        val entity = field(10, "spotify:track:example")
        assertArrayEquals(byteArrayOf(10, entity.size.toByte()) + entity,
            CanvasProtocol.request("spotify:track:example"))
    }

    @Test fun responseRetainsEntityIdentityAndVideoType() {
        val entry = field(18, "https://canvaz.scdn.co/video.mp4") +
            field(26, "abcdef0123456789") + byteArrayOf(32, 2) +
            field(42, "spotify:track:example")
        val response = byteArrayOf(10, entry.size.toByte()) + entry + byteArrayOf(16, 60)
        val parsed = CanvasProtocol.entries(response)!!.single()
        assertEquals("spotify:track:example", parsed.uri)
        assertEquals("https://canvaz.scdn.co/video.mp4", parsed.url)
        assertEquals(2L, parsed.type)
        assertTrue(CanvasProtocol.entries(byteArrayOf(16, 60))!!.isEmpty())
    }

    @Test fun truncatedAndOverflowingMessagesAreRejected() {
        assertNull(CanvasProtocol.entries(byteArrayOf(10, 127, 1)))
        assertNull(CanvasProtocol.entries(ByteArray(12) { 0x80.toByte() }))
        assertNull(CanvasProtocol.entries(byteArrayOf(0)))
    }
}
