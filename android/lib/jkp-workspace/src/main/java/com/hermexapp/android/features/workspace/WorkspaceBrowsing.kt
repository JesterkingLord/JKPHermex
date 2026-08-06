package com.hermexapp.android.features.workspace

import com.hermexapp.android.model.WorkspaceEntry

/**
 * How the file list is ordered.
 *
 * Directories always come first in both orders. Mixing them into a date sort
 * scatters folders through the list and makes the tree unreadable, which is
 * why every file browser worth using pins them to the top.
 */
enum class WorkspaceSort {
    /** A–Z. Predictable; the right default when you know what you are after. */
    NAME,

    /** Most recently modified first — what a session just produced. */
    NEWEST,
}

/**
 * One crumb in the path bar: what to show, and where tapping it goes.
 *
 * [path] is what `/api/list` expects — `null` for the workspace root, since
 * the endpoint treats a missing path as ".".
 */
data class WorkspaceCrumb(val label: String, val path: String?)

/**
 * Orders [entries] for display.
 *
 * `mtime_ns` can be absent (the server omits it when it cannot stat the entry),
 * so a missing timestamp sorts last rather than jumping to the top as a zero
 * would.
 */
fun sortWorkspaceEntries(
    entries: List<WorkspaceEntry>,
    sort: WorkspaceSort = WorkspaceSort.NAME,
): List<WorkspaceEntry> {
    val directoriesFirst = compareByDescending<WorkspaceEntry> { it.isBrowsableDirectory }
    return when (sort) {
        WorkspaceSort.NAME ->
            entries.sortedWith(directoriesFirst.thenBy { it.name?.lowercase() ?: "" })

        WorkspaceSort.NEWEST ->
            entries.sortedWith(
                directoriesFirst
                    .thenByDescending { it.mtimeNs ?: Long.MIN_VALUE }
                    .thenBy { it.name?.lowercase() ?: "" },
            )
    }
}

/**
 * Splits a workspace-relative path into crumbs, root first.
 *
 * `/api/list` paths are relative and slash-separated ("src/main/kotlin"), and
 * "." means the root. Empty segments are dropped so a stray or trailing slash
 * cannot produce a blank crumb that navigates nowhere.
 */
fun workspaceCrumbs(path: String?, rootLabel: String = "Workspace"): List<WorkspaceCrumb> {
    val root = WorkspaceCrumb(rootLabel, null)
    val clean = path?.trim()?.trim('/')
    if (clean.isNullOrEmpty() || clean == ".") return listOf(root)

    val segments = clean.split('/').filter { it.isNotBlank() }
    return buildList {
        add(root)
        segments.forEachIndexed { index, segment ->
            add(WorkspaceCrumb(segment, segments.take(index + 1).joinToString("/")))
        }
    }
}

/**
 * The directory containing [path], or `null` when [path] is already at the
 * root — which the caller reads as "there is nowhere further up".
 */
fun workspaceParentPath(path: String?): String? {
    val clean = path?.trim()?.trim('/')
    if (clean.isNullOrEmpty() || clean == ".") return null
    val cut = clean.lastIndexOf('/')
    return if (cut <= 0) null else clean.substring(0, cut)
}

/**
 * Broad kind of a workspace entry, decided from its name.
 *
 * The server does not classify files — `/api/list` reports `type` as only
 * "dir", "file" or "symlink" — so the kind is derived here from the extension.
 */
enum class WorkspaceFileKind(val glyph: String) {
    FOLDER("📁"),
    IMAGE("🖼️"),
    VIDEO("🎬"),
    AUDIO("🎵"),
    DOCUMENT("📄"),
    CODE("📝"),
    ARCHIVE("🗜️"),
    /** A symlink that leaves the workspace: shown, but never navigable. */
    BLOCKED("⛔"),
    OTHER("📄"),
}

private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "svg", "ico", "avif")
private val videoExtensions = setOf("mp4", "mov", "webm", "mkv", "avi", "m4v")
private val audioExtensions = setOf("mp3", "wav", "ogg", "flac", "m4a", "aac")
private val documentExtensions = setOf("pdf", "md", "txt", "rtf", "doc", "docx", "odt", "csv", "xlsx")
private val archiveExtensions = setOf("zip", "tar", "gz", "7z", "rar", "bz2", "xz")
private val codeExtensions = setOf(
    "kt", "kts", "java", "swift", "py", "js", "ts", "tsx", "jsx", "go", "rs", "rb", "c", "h",
    "cpp", "hpp", "cs", "sh", "ps1", "bat", "json", "yaml", "yml", "toml", "xml", "html", "css",
    "sql", "gradle", "properties",
)

/**
 * Classifies [entry] for display.
 *
 * An escaping symlink is reported as [WorkspaceFileKind.BLOCKED] before
 * anything else: the server withholds its target and refuses to read through
 * it, so showing it as an ordinary file would invite a tap that can only fail.
 *
 * A name with no extension is [WorkspaceFileKind.OTHER] rather than a guess —
 * "LICENSE" and "Makefile" are not usefully any of these categories.
 */
fun workspaceFileKind(entry: WorkspaceEntry): WorkspaceFileKind {
    if (entry.targetOutsideWorkspace == true) return WorkspaceFileKind.BLOCKED
    if (entry.isBrowsableDirectory) return WorkspaceFileKind.FOLDER

    val name = entry.name ?: entry.path.orEmpty()
    val dot = name.lastIndexOf('.')
    // A leading dot is a hidden file (".gitignore"), not an extension.
    if (dot <= 0 || dot == name.length - 1) return WorkspaceFileKind.OTHER

    return when (name.substring(dot + 1).lowercase()) {
        in imageExtensions -> WorkspaceFileKind.IMAGE
        in videoExtensions -> WorkspaceFileKind.VIDEO
        in audioExtensions -> WorkspaceFileKind.AUDIO
        in documentExtensions -> WorkspaceFileKind.DOCUMENT
        in archiveExtensions -> WorkspaceFileKind.ARCHIVE
        in codeExtensions -> WorkspaceFileKind.CODE
        else -> WorkspaceFileKind.OTHER
    }
}
