package com.project.puk.core

import org.junit.Assert.*
import org.junit.Test

class SavedAccountsTest {
    private fun session(id: String, cookie: String = "session-$id") = SavedAccountSession(
        Auth.Account(id, "Name $id", "$id@example.com", "https://image/$id", "", "PL"), cookie)

    @Test fun roundTripKeepsIdentitiesAndTheirSessionsTogether() {
        val entries = listOf(session("first"), session("second"))
        assertEquals(entries, SavedAccounts.decode(SavedAccounts.encode(entries)))
    }

    @Test fun reloginUpdatesOnlyTheMatchingAccount() {
        val first = session("first")
        val renewed = session("second", "renewed-session")
        assertEquals(listOf(first, renewed), SavedAccounts.upsert(listOf(first, session("second")), renewed))
    }

    @Test fun malformedOrMismatchedIdentityIsNotSelectable() {
        assertTrue(SavedAccounts.decode("not json").isEmpty())
        assertTrue(SavedAccounts.decode("""{"first":{"profile":{"id":"second"},"cookies":"session"}}""").isEmpty())
        assertTrue(SavedAccounts.decode(null).isEmpty())
    }
}
