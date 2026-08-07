package com.hermexapp.android.features.workspace

import com.hermexapp.android.model.SessionSummary
import com.hermexapp.android.model.WorkspaceEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cross-session merge (Files slice 5).
 *
 * All of it is pure, which is the point: the fan-out itself is boring, and
 * every way this feature can be wrong — showing one file twice, merging two
 * different files into one row, or claiming to cover sessions it never read —
 * lives in these rules.
 */
class RecentFilesTest {

    private fun file(name: String, mtime: Long? = null, path: String = name) =
        WorkspaceEntry(name = name, path = path, type = "file", mtimeNs = mtime)

    private fun source(id: String, workspace: String? = null, title: String? = null) =
        RecentFileSource(sessionId = id, sessionTitle = title, workspace = workspace)

    // ── Which sessions get scanned ──

    @Test
    fun `scans the most recently updated sessions first and stops at the limit`() {
        val sessions = listOf(
            SessionSummary(sessionId = "old", updatedAt = 100.0),
            SessionSummary(sessionId = "newest", updatedAt = 300.0),
            SessionSummary(sessionId = "middle", updatedAt = 200.0),
        )

        val scanned = recentSessionsToScan(sessions, limit = 2)

        assertEquals(listOf("newest", "middle"), scanned.map { it.sessionId })
    }

    @Test
    fun `a session with no timestamp sorts last rather than first`() {
        // Missing updatedAt read as 0.0 would be fine; read as "now" it would
        // crowd out the sessions the user actually just worked in.
        val sessions = listOf(
            SessionSummary(sessionId = "unknown-time", updatedAt = null),
            SessionSummary(sessionId = "dated", updatedAt = 5.0),
        )

        assertEquals(
            listOf("dated", "unknown-time"),
            recentSessionsToScan(sessions).map { it.sessionId },
        )
    }

    @Test
    fun `archived sessions are not scanned`() {
        // Archiving is the user saying they are done; those files are not
        // what "recent" means, and scanning them wastes one of the N calls.
        val sessions = listOf(
            SessionSummary(sessionId = "live", updatedAt = 1.0),
            SessionSummary(sessionId = "archived", updatedAt = 999.0, archived = true),
        )

        assertEquals(listOf("live"), recentSessionsToScan(sessions).map { it.sessionId })
    }

    @Test
    fun `a session without an id is skipped rather than scanned with a blank`() {
        val sessions = listOf(
            SessionSummary(sessionId = null, updatedAt = 999.0),
            SessionSummary(sessionId = "  ", updatedAt = 998.0),
            SessionSummary(sessionId = "real", updatedAt = 1.0),
        )

        assertEquals(listOf("real"), recentSessionsToScan(sessions).map { it.sessionId })
    }

    // ── The merge ──

    @Test
    fun `newest file across all sessions comes first`() {
        val merged = mergeRecentFiles(
            listOf(
                SessionListing(source("a", "/ws/a"), listOf(file("old.kt", 100))),
                SessionListing(source("b", "/ws/b"), listOf(file("new.kt", 300))),
                SessionListing(source("c", "/ws/c"), listOf(file("mid.kt", 200))),
            ),
        )

        assertEquals(listOf("new.kt", "mid.kt", "old.kt"), merged.map { it.entry.name })
    }

    @Test
    fun `the same file seen in two sessions of one workspace is one row, newest kept`() {
        val merged = mergeRecentFiles(
            listOf(
                SessionListing(source("first", "/ws"), listOf(file("notes.md", 100))),
                SessionListing(source("second", "/ws"), listOf(file("notes.md", 500))),
            ),
        )

        assertEquals(1, merged.size)
        assertEquals(500L, merged.single().entry.mtimeNs)
    }

    @Test
    fun `the same path in two workspaces stays two rows`() {
        // README.md in two projects is two files. Keying on path alone would
        // hide one of them behind the other.
        val merged = mergeRecentFiles(
            listOf(
                SessionListing(source("a", "/ws/alpha"), listOf(file("README.md", 100))),
                SessionListing(source("b", "/ws/beta"), listOf(file("README.md", 200))),
            ),
        )

        assertEquals(2, merged.size)
        assertEquals(
            listOf("/ws/beta", "/ws/alpha"),
            merged.map { it.source.workspace },
        )
    }

    @Test
    fun `two sessions with no known workspace are not merged together`() {
        // Falling back to the session id keeps unknowns apart; a shared blank
        // namespace would collapse unrelated files into one wrong row.
        val merged = mergeRecentFiles(
            listOf(
                SessionListing(source("a", workspace = null), listOf(file("main.kt", 100))),
                SessionListing(source("b", workspace = null), listOf(file("main.kt", 200))),
            ),
        )

        assertEquals(2, merged.size)
    }

    @Test
    fun `directories are dropped`() {
        val merged = mergeRecentFiles(
            listOf(
                SessionListing(
                    source("a", "/ws"),
                    listOf(
                        WorkspaceEntry(name = "src", path = "src", type = "dir", mtimeNs = 900),
                        file("build.gradle.kts", 100),
                    ),
                ),
            ),
        )

        assertEquals(listOf("build.gradle.kts"), merged.map { it.entry.name })
    }

    @Test
    fun `a symlink that leaves the workspace is dropped, not shown as a file`() {
        // The server refuses to read through it, so a row here could only fail
        // when tapped — and this is the list where an unreachable row is most
        // confusing, because you cannot see which tree it came from.
        val merged = mergeRecentFiles(
            listOf(
                SessionListing(
                    source("a", "/ws"),
                    listOf(
                        WorkspaceEntry(
                            name = "escape",
                            path = "escape",
                            type = "symlink",
                            mtimeNs = 999,
                            targetOutsideWorkspace = true,
                        ),
                        file("safe.txt", 1),
                    ),
                ),
            ),
        )

        assertEquals(listOf("safe.txt"), merged.map { it.entry.name })
    }

    @Test
    fun `a file with no timestamp sorts last`() {
        val merged = mergeRecentFiles(
            listOf(
                SessionListing(
                    source("a", "/ws"),
                    listOf(file("unstattable.bin", null), file("dated.kt", 1)),
                ),
            ),
        )

        assertEquals(listOf("dated.kt", "unstattable.bin"), merged.map { it.entry.name })
    }

    @Test
    fun `an entry with no path is skipped because it cannot be opened`() {
        val merged = mergeRecentFiles(
            listOf(
                SessionListing(
                    source("a", "/ws"),
                    listOf(WorkspaceEntry(name = "ghost", path = null, type = "file")),
                ),
            ),
        )

        assertTrue(merged.isEmpty())
    }

    @Test
    fun `the limit caps the merged list`() {
        val entries = (1..20).map { file("f$it.txt", it.toLong()) }
        val merged = mergeRecentFiles(listOf(SessionListing(source("a", "/ws"), entries)), limit = 5)

        assertEquals(5, merged.size)
        assertEquals("f20.txt", merged.first().entry.name)
    }

    @Test
    fun `rows from different workspaces get distinct list keys`() {
        // LazyColumn keys must be unique or Compose throws at runtime, and the
        // same filename in two workspaces is exactly the collision to expect.
        val merged = mergeRecentFiles(
            listOf(
                SessionListing(source("a", "/ws/alpha"), listOf(file("README.md", 1))),
                SessionListing(source("b", "/ws/beta"), listOf(file("README.md", 2))),
            ),
        )

        assertEquals(2, merged.map { it.stableId }.distinct().size)
    }

    // ── The uploaded-here filter (slice 4) ──

    @Test
    fun the_all_filter_shows_everything_regardless_of_the_ledger() {
        val files = mergeRecentFiles(
            listOf(SessionListing(source("a", "/ws"), listOf(file("x.txt", 1), file("y.txt", 2)))),
        )

        assertEquals(2, filterRecentFiles(files, RecentFileFilter.ALL) { false }.size)
    }

    @Test
    fun the_uploaded_filter_shows_only_what_this_device_sent() {
        val files = mergeRecentFiles(
            listOf(
                SessionListing(
                    source("a", "/ws"),
                    listOf(file("mine.png", 2), file("written-by-agent.kt", 1)),
                ),
            ),
        )

        val uploaded = filterRecentFiles(files, RecentFileFilter.UPLOADED_HERE) {
            it == "mine.png"
        }

        assertEquals(listOf("mine.png"), uploaded.map { it.entry.name })
    }

    @Test
    fun the_uploaded_filter_is_empty_rather_than_falling_back_to_everything() {
        // An empty filter must not quietly show all files: the operator would
        // read every one of them as uploaded from this phone.
        val files = mergeRecentFiles(
            listOf(SessionListing(source("a", "/ws"), listOf(file("x.txt", 1)))),
        )

        assertTrue(filterRecentFiles(files, RecentFileFilter.UPLOADED_HERE) { false }.isEmpty())
    }

    @Test
    fun the_empty_message_explains_where_pc_uploads_went() {
        // "No files" would read as a bug when All plainly has files.
        val message = recentFilesEmptyMessage(RecentFileFilter.UPLOADED_HERE)

        assertTrue(message, message.contains("this device"))
        assertTrue(message, message.contains("All files"))
    }

    @Test
    fun the_filter_labels_never_claim_to_know_what_was_created() {
        // The host records no provenance, so "Created" would be a claim the
        // app cannot back with anything.
        RecentFileFilter.entries.forEach { filter ->
            assertTrue(filter.label, !filter.label.lowercase().contains("created"))
        }
    }

    // ── What a row says about where the file came from ──

    @Test
    fun `a real session name is used as the label`() {
        assertEquals(
            "EFER Gauntlet Loop",
            recentFileSourceLabel(source("s1", "/ws/efer", "EFER Gauntlet Loop")),
        )
    }

    @Test
    fun `a pasted prompt is not used as a label`() {
        // Seen on the device: every row read the same truncated prompt, which
        // placed nothing. The workspace name is what a person calls a project.
        val pastedPrompt =
            "<local-command-caveat>Caveat: The messages below were generated while running a command"

        assertEquals(
            "efer",
            recentFileSourceLabel(source("s1", "C:\\Users\\roflm\\workspace\\efer", pastedPrompt)),
        )
    }

    @Test
    fun `a multi-line title falls back even when it is short`() {
        // Two lines in a one-line slot renders as a squashed fragment.
        assertEquals(
            "beta",
            recentFileSourceLabel(source("s1", "/ws/beta", "line one\nline two")),
        )
    }

    @Test
    fun `a trailing separator does not produce a blank label`() {
        assertEquals("alpha", recentFileSourceLabel(source("s1", "/ws/alpha/", null)))
    }

    @Test
    fun `with neither title nor workspace the label falls back to the session id`() {
        // Never blank: an unlabelled row cannot be told apart from its neighbour.
        assertEquals("abcdefgh", recentFileSourceLabel(source("abcdefgh-1234", null, null)))
    }

    // ── What the header claims ──

    @Test
    fun `subtitle names the session count so it never implies it read them all`() {
        val state = RecentFilesViewModel.UiState(hasLoaded = true, sessionsAttempted = 10)

        assertEquals("From your last 10 sessions", recentFilesSubtitle(state))
    }

    @Test
    fun `subtitle admits when some sessions could not be read`() {
        val state = RecentFilesViewModel.UiState(
            hasLoaded = true,
            sessionsAttempted = 10,
            sessionsFailed = 2,
        )

        assertEquals("From 8 of your last 10 sessions", recentFilesSubtitle(state))
    }

    @Test
    fun `subtitle says nothing before the first load`() {
        assertNull(recentFilesSubtitle(RecentFilesViewModel.UiState()))
    }

    @Test
    fun `every session failing is not reported as a partial success`() {
        val state = RecentFilesViewModel.UiState(
            hasLoaded = true,
            sessionsAttempted = 3,
            sessionsFailed = 3,
        )

        assertTrue(!state.isPartial)
    }
}
