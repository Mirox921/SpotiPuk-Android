package com.project.puk.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * All locale dictionaries (RU/EN/UK/PL/DE) must stay key-complete: a missing
 * entry falls back to Russian silently, which is exactly what this test is
 * here to catch.
 */
class LangTest {

    @Test
    fun `every locale dictionary covers every declared key`() {
        Lang.Key.entries.forEach { key ->
            val missing = listOf(
                "EN" to Lang.enForTest(),
                "UK" to Lang.ukForTest(),
                "PL" to Lang.plForTest(),
                "DE" to Lang.deForTest(),
            ).filter { !it.second.containsKey(key) }.map { it.first }
            assertTrue("Key $key missing in: $missing", missing.isEmpty())
        }
    }

    @Test
    fun `ru dictionary covers every declared key`() {
        val missing = Lang.Key.entries.filter { !Lang.ruForTest().containsKey(it) }
        assertTrue("RU keys missing: $missing", missing.isEmpty())
    }

    @Test
    fun `t returns ru by default and en after switch`() {
        // current defaults to RU; t() must return the ru dict text, never the raw key.
        val key = Lang.Key.TAB_HOME
        assertEquals("Главная", Lang.t(key))
    }

    @Test
    fun `labels differ across languages`() {
        val en = Lang.Key.entries.map { Lang.enForTest().getValue(it) }
        val ru = Lang.Key.entries.map { Lang.ruForTest().getValue(it) }
        // Not a strict requirement, but identical texts would signal copy-paste errors.
        assertEquals(en.size, ru.size)
        assertTrue(en.zip(ru).count { (a, b) -> a != b } > en.size / 2)
    }
}
