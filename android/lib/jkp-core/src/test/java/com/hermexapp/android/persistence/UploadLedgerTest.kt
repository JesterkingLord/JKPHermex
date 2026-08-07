package com.hermexapp.android.persistence

import com.hermexapp.android.config.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upload ledger (Files slice 4).
 *
 * This is the only place in the system that knows a file came from this phone
 * — the host records no provenance — so the failure mode that matters is
 * claiming a file it should not.
 */
class UploadLedgerTest {

    private fun ledger(store: InMemoryKeyValueStore = InMemoryKeyValueStore()) =
        UploadLedger(store)

    @Test
    fun a_recorded_upload_is_recognised_by_its_path() {
        val ledger = ledger()
        ledger.record("uploads/report.pdf", "report.pdf", 1_000)

        assertTrue(ledger.wasUploadedFromThisDevice("uploads/report.pdf"))
    }

    @Test
    fun a_file_this_device_never_uploaded_is_not_claimed() {
        // The whole point of the label. A file written by an agent, or dropped
        // in from the PC, must fall outside it.
        val ledger = ledger()
        ledger.record("uploads/mine.png", "mine.png", 1_000)

        assertFalse(ledger.wasUploadedFromThisDevice("src/main/Agent.kt"))
        assertFalse(ledger.wasUploadedFromThisDevice(null))
    }

    @Test
    fun matching_is_on_path_not_filename() {
        // Two files can share a name in different directories; claiming by
        // name would label a file the operator never sent.
        val ledger = ledger()
        ledger.record("uploads/README.md", "README.md", 1_000)

        assertFalse(ledger.wasUploadedFromThisDevice("docs/README.md"))
    }

    @Test
    fun re_uploading_the_same_path_updates_it_rather_than_duplicating() {
        // The library shows files, not events.
        val ledger = ledger()
        ledger.record("uploads/a.txt", "a.txt", 1_000)
        ledger.record("uploads/a.txt", "a.txt", 5_000)

        assertEquals(1, ledger.all().size)
        assertEquals(5_000L, ledger.all().single().uploadedAtMillis)
    }

    @Test
    fun the_newest_upload_is_first() {
        val ledger = ledger()
        ledger.record("one.txt", "one.txt", 1_000)
        ledger.record("two.txt", "two.txt", 2_000)

        assertEquals(listOf("two.txt", "one.txt"), ledger.all().map { it.path })
    }

    @Test
    fun a_blank_path_is_not_recorded() {
        // An empty path can never match a listing, so a row for it is noise
        // that would also push a real entry out of the cap.
        val ledger = ledger()
        ledger.record("", "ghost.txt", 1_000)

        assertTrue(ledger.all().isEmpty())
    }

    @Test
    fun the_ledger_is_capped_and_drops_the_oldest() {
        val store = InMemoryKeyValueStore()
        val ledger = UploadLedger(store, maxEntries = 3)
        repeat(5) { ledger.record("f$it.txt", "f$it.txt", it.toLong()) }

        assertEquals(3, ledger.all().size)
        assertEquals(listOf("f4.txt", "f3.txt", "f2.txt"), ledger.all().map { it.path })
    }

    @Test
    fun it_survives_being_reopened() {
        val store = InMemoryKeyValueStore()
        UploadLedger(store).record("kept.txt", "kept.txt", 1_000)

        assertTrue(UploadLedger(store).wasUploadedFromThisDevice("kept.txt"))
    }

    @Test
    fun unreadable_stored_data_is_dropped_rather_than_thrown() {
        // Losing the ledger costs a file its label; crashing costs the screen.
        val store = InMemoryKeyValueStore()
        store.putString("upload_ledger_v1", "{ not json at all")

        assertTrue(UploadLedger(store).all().isEmpty())
    }

    @Test
    fun clearing_forgets_everything_including_after_a_reopen() {
        // Sign-out: one device's history must not describe another's.
        val store = InMemoryKeyValueStore()
        val ledger = UploadLedger(store)
        ledger.record("a.txt", "a.txt", 1_000)

        ledger.clear()

        assertFalse(ledger.wasUploadedFromThisDevice("a.txt"))
        assertFalse(UploadLedger(store).wasUploadedFromThisDevice("a.txt"))
    }
}
