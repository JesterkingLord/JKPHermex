package com.hermexapp.android.features.workspace

import com.hermexapp.android.model.SessionSummary
import com.hermexapp.android.model.WorkspaceEntry

/**
 * "Recent files" across sessions (Files library, slice 5).
 *
 * `/api/list` is per session, so there is no single call for this — the caller
 * fans out over several sessions and merges the results here. Everything in
 * this file is pure so the merge rules, which are where the mistakes live, can
 * be tested without a server.
 */

/** Which session a file was seen in, kept so a row can say where it came from. */
data class RecentFileSource(
    val sessionId: String,
    val sessionTitle: String? = null,
    val workspace: String? = null,
)

/** One session's directory listing, as returned by `/api/list`. */
data class SessionListing(
    val source: RecentFileSource,
    val entries: List<WorkspaceEntry>,
)

/** A file in the merged view, and the session that surfaced it. */
data class RecentFile(
    val entry: WorkspaceEntry,
    val source: RecentFileSource,
) {
    val stableId: String get() = "${source.workspace ?: source.sessionId}::${entry.stableId}"
}

/**
 * The sessions worth scanning, most recently updated first.
 *
 * The fan-out is one request per session, so it has to be bounded: sixty
 * sessions is sixty requests, which is not something to fire on screen entry.
 * The caller shows [limit] in the UI ("your last 10 sessions") rather than
 * implying the list covers everything.
 *
 * Archived sessions are skipped — archiving is the user saying they are done
 * with it, and their files are not what "recent" means.
 */
fun recentSessionsToScan(
    sessions: List<SessionSummary>,
    limit: Int = DEFAULT_SESSION_SCAN_LIMIT,
): List<RecentFileSource> = sessions
    .asSequence()
    .filter { it.archived != true }
    .mapNotNull { summary ->
        val id = summary.sessionId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        summary to RecentFileSource(id, summary.title, summary.workspace)
    }
    // updatedAt is optional; a session that never reported one sorts last
    // rather than ahead of sessions with a real timestamp.
    .sortedByDescending { (summary, _) -> summary.updatedAt ?: Double.NEGATIVE_INFINITY }
    .map { (_, source) -> source }
    .take(limit)
    .toList()

/**
 * Merges per-session listings into one newest-first list of files.
 *
 * The rules, each of which is a way this can go wrong:
 *
 *  - **Directories are dropped.** This is a file list; folders belong to the
 *    browser, where you can walk into them.
 *  - **Escaping symlinks are dropped.** The server refuses to read through
 *    them, so a row here could only ever fail when tapped — and an unreachable
 *    row is most confusing precisely in a list that spans sessions.
 *  - **Identity is workspace + path**, because the same relative path in two
 *    workspaces is two different files, while the same path in one workspace
 *    reached from two sessions is one file. When the workspace is unknown the
 *    session id stands in, so two unknowns are kept apart rather than merged
 *    into a single wrong row.
 *  - **The newest copy wins** a duplicate, so the timestamp shown is the one
 *    the file actually has.
 *  - **A missing `mtime_ns` sorts last**, matching [sortWorkspaceEntries]; a
 *    null treated as zero would be fine, but a null treated as "now" would put
 *    unstattable files at the top of a list that claims to be newest-first.
 */
fun mergeRecentFiles(
    listings: List<SessionListing>,
    limit: Int = DEFAULT_RECENT_FILE_LIMIT,
): List<RecentFile> {
    val newestByFile = LinkedHashMap<String, RecentFile>()

    for (listing in listings) {
        for (entry in listing.entries) {
            if (entry.isBrowsableDirectory) continue
            if (entry.targetOutsideWorkspace == true) continue
            if (entry.type == "dir") continue
            val path = entry.path?.takeIf { it.isNotBlank() } ?: continue

            val namespace = listing.source.workspace?.takeIf { it.isNotBlank() }
                ?: listing.source.sessionId
            val key = "$namespace::$path"

            val candidate = RecentFile(entry, listing.source)
            val existing = newestByFile[key]
            if (existing == null ||
                (entry.mtimeNs ?: Long.MIN_VALUE) > (existing.entry.mtimeNs ?: Long.MIN_VALUE)
            ) {
                newestByFile[key] = candidate
            }
        }
    }

    return newestByFile.values
        .sortedWith(
            compareByDescending<RecentFile> { it.entry.mtimeNs ?: Long.MIN_VALUE }
                .thenBy { it.entry.name?.lowercase() ?: it.entry.path.orEmpty().lowercase() },
        )
        .take(limit)
}

/**
 * Which files the recent-files list is showing.
 *
 * Deliberately not "created" against "uploaded". The host records no
 * provenance at all, so "created" is not something the app can know — only
 * "this device uploaded it" is, from its own ledger. Naming the tabs after
 * what is actually knowable keeps the UI from making a claim it cannot back.
 */
enum class RecentFileFilter(val label: String) {
    ALL("All files"),

    /**
     * Uploaded *from this device*. A file sent from the operator's PC, or
     * written by an agent, is not distinguishable from any other file on disk
     * and correctly falls outside this.
     */
    UPLOADED_HERE("Uploaded from this device"),
}

/**
 * Applies [filter] to [files].
 *
 * @param wasUploadedHere asks the ledger whether this device uploaded a path.
 */
fun filterRecentFiles(
    files: List<RecentFile>,
    filter: RecentFileFilter,
    wasUploadedHere: (String?) -> Boolean,
): List<RecentFile> = when (filter) {
    RecentFileFilter.ALL -> files
    RecentFileFilter.UPLOADED_HERE -> files.filter { wasUploadedHere(it.entry.path) }
}

/**
 * What to say when a filter matches nothing.
 *
 * The empty ledger case needs its own sentence. "No files" would read as a
 * bug — the files are plainly there under All — when the real answer is that
 * this device has not uploaded any yet.
 */
fun recentFilesEmptyMessage(filter: RecentFileFilter): String = when (filter) {
    RecentFileFilter.ALL -> "No files in your recent sessions yet."
    RecentFileFilter.UPLOADED_HERE ->
        "Nothing uploaded from this device yet. Files added from your PC are under All files."
}

/**
 * A short label for where a file came from.
 *
 * The obvious choice — the session title — is often not a name at all. Sessions
 * started from a pasted prompt are titled with that prompt, so on the device
 * every row read "<local-command-caveat>Caveat: The messages b…", which places
 * nothing and merely fills the line. A title is used only when it looks like a
 * name: one line, and short enough to be one.
 *
 * The fallback is the workspace's last segment, which is what a person calls a
 * project, and finally a truncated session id — ugly, but never blank, and it
 * still tells two rows apart.
 */
fun recentFileSourceLabel(source: RecentFileSource): String {
    val title = source.sessionTitle?.trim()
    if (!title.isNullOrEmpty() && title.length <= MAX_TITLE_AS_LABEL && !title.contains('\n')) {
        return title
    }

    val workspaceLeaf = source.workspace
        ?.trim()
        ?.trimEnd('/', '\\')
        ?.split('/', '\\')
        ?.lastOrNull()
        ?.takeIf { it.isNotBlank() }
    if (workspaceLeaf != null) return workspaceLeaf

    return source.sessionId.take(8)
}

/** Longer than this and a "title" is a pasted prompt, not a name. */
private const val MAX_TITLE_AS_LABEL = 48

/** Sessions scanned per refresh. Ten is a few seconds of requests, not a minute. */
const val DEFAULT_SESSION_SCAN_LIMIT = 10

/** Rows kept after merging. Beyond this nobody is scrolling to find anything. */
const val DEFAULT_RECENT_FILE_LIMIT = 60
