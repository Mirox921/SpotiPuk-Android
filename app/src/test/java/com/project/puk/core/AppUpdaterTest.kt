package com.project.puk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun `parse reads version url and changelog`() {
        val json = """
            {"packageName":"com.project.puk","versionName":"2.0.0-alpha2","versionCode":13,
             "url":"https://spotipuk.stream/downloads/x.apk","sha256":"ab12",
             "notes":"meta ignored","changelog":["Улучшение A","Исправление B"]}
        """.trimIndent()
        val info = AppUpdater.parse(json)!!
        assertEquals(13, info.versionCode)
        assertEquals("2.0.0-alpha2", info.versionName)
        assertEquals("https://spotipuk.stream/downloads/x.apk", info.url)
        assertEquals("ab12", info.sha256)
        assertEquals(listOf("Улучшение A", "Исправление B"), info.notes)
    }

    @Test
    fun `parse survives malformed json and missing fields`() {
        assertNull(AppUpdater.parse("not json at all"))
        assertNull(AppUpdater.parse("""{"versionName":"x"}""")) // no versionCode
        assertNull(AppUpdater.parse("""{"versionCode":13}""")) // no url
        // Empty changelog is fine, sha256 optional.
        val info = AppUpdater.parse("""{"versionCode":2,"url":"u"}""")!!
        assertEquals(2, info.versionCode)
        assertTrue(info.notes.isEmpty())
        assertNull(info.sha256)
    }

    @Test
    fun `isNewer compares versionCode`() {
        val info = AppUpdater.parse("""{"versionCode":13,"url":"u"}""")!!
        assertTrue(AppUpdater.isNewer(info, 12))
        assertFalse(AppUpdater.isNewer(info, 13))
        assertFalse(AppUpdater.isNewer(info, 14))
        assertFalse(AppUpdater.isNewer(null, 1))
    }
}
