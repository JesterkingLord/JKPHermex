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
    return workspaceFileKind(entry.name ?: entry.path.orEmpty())
}

/**
 * Classifies by filename alone, for the paths that arrive without an entry —
 * a recent-files tap, or reopening a path the listing no longer holds.
 *
 * Callers that have a [WorkspaceEntry] should use the overload above: it can
 * still tell a folder or an escaping symlink apart, and a name cannot.
 */
fun workspaceFileKind(name: String): WorkspaceFileKind {
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

/**
 * Image extensions classified as images that must not be previewed inline.
 *
 * SVG only, for two independent reasons that agree. The feature plan excludes
 * it by name — "SVG deliberately excluded from inline preview, matching the
 * server's own rule" — and the server does hold it apart: `image/svg+xml`
 * appears in both `_TEXT_MIME_TYPES` and `dangerous_types`, and is served as
 * an attachment rather than inline, because an SVG is a document that can
 * carry script.
 *
 * The practical half agrees: `BitmapFactory` cannot decode SVG, so routing one
 * to the preview produced "named like an image but could not be decoded" —
 * true, and useless. An SVG is XML text, so the text read shows its actual
 * markup, which is the answer worth having.
 *
 * The listing still shows the image glyph, because it is an image. Only the
 * preview declines.
 */
private val nonPreviewableImageExtensions = setOf("svg")

/**
 * True when [name] is an image this screen can actually draw inline.
 *
 * Deliberately not `workspaceFileKind(name) == IMAGE`. The classifier answers
 * "what kind of file is this" — a question about the file. This answers "can
 * we render it here" — a question about us and the server's rules. Conflating
 * the two is what sent SVG to a decoder that cannot read it.
 */
fun workspaceIsInlinePreviewableImage(name: String): Boolean =
    workspaceFileKind(name) == WorkspaceFileKind.IMAGE &&
        name.substringAfterLast('.', "").lowercase() !in nonPreviewableImageExtensions

/**
 * True when reading [name] through `/api/file` can only produce mojibake.
 *
 * The host ends `read_file_content` in `raw.decode('utf-8', errors='replace')`,
 * so any non-text file comes back as a wall of U+FFFD with no error — the same
 * defect images had before they were routed to `/api/media`.
 *
 * The set is deliberately narrow, because the obvious wider rule breaks things:
 *  - `.md`, `.txt`, `.csv` share [WorkspaceFileKind.DOCUMENT] with `.pdf` but
 *    are genuinely text, so excluding the whole kind would stop ordinary files
 *    from opening;
 *  - `.docx`, `.xlsx` and `.pptx` are binary yet **do** preview, because the
 *    host routes them through `preview_office_document` before decoding.
 *
 * That leaves video, audio, archives and `.pdf` — checked by extension rather
 * than by kind precisely because PDF's kind is shared with real text.
 *
 * Images are absent on purpose: they have somewhere better to go.
 */
fun workspaceIsUnreadableAsText(name: String): Boolean {
    if (name.substringAfterLast('.', "").lowercase() in binaryDocumentExtensions) return true
    return when (workspaceFileKind(name)) {
        WorkspaceFileKind.VIDEO, WorkspaceFileKind.AUDIO, WorkspaceFileKind.ARCHIVE -> true
        else -> false
    }
}

/**
 * The binary members of [documentExtensions].
 *
 * That set mixes formats that share nothing but a category. Splitting it by
 * what `/api/file` can actually return:
 *  - `pdf` is binary and the host has no preview path for it;
 *  - `doc` is an OLE compound file and `odt` is a zip — both binary, and
 *    neither is one of the three the host converts;
 *  - `docx`, `xlsx` and `pptx` are binary too but **do** come back readable,
 *    because the host routes exactly those through `preview_office_document`;
 *  - `md`, `txt`, `csv` and `rtf` are text — RTF is ASCII markup, ugly to read
 *    raw but not mojibake — so they must keep their text read.
 *
 * `doc` and `odt` were missed on the first pass: the rule was written as "pdf"
 * rather than "the binary ones", which is the same shortcut, one layer down.
 */
private val binaryDocumentExtensions = setOf("pdf", "doc", "odt")

/**
 * Joins a workspace [root] and a workspace-relative [relativePath] into the
 * absolute path `/api/media` requires, or `null` when it cannot be built.
 *
 * Returning null is the point. `/api/media` takes no `session_id` and answers a
 * relative path with **403**, so without a root there is nothing to ask for —
 * the caller must skip the request rather than spend a round trip that can only
 * fail, and the root is not always known (the deployed `/api/list` omits it).
 *
 * Separators follow the root, because the host is whatever the server runs on:
 * a Windows root keeps backslashes, a POSIX root keeps forward slashes. The
 * server accepts either form for a Windows path, but echoing the root's own
 * style keeps what we send recognisable as what it reported.
 *
 * `.` — how `/api/list` spells the workspace root — resolves to the root
 * itself, and traversal segments are refused here rather than sent onward: the
 * server blocks them anyway, and a request built to be rejected is a bug worth
 * catching on this side.
 */
fun workspaceAbsolutePath(root: String?, relativePath: String?): String? {
    val cleanRoot = root?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val rel = relativePath?.trim()?.replace('\\', '/')?.trim('/') ?: return null

    val separator = if (cleanRoot.contains('\\') && !cleanRoot.startsWith("/")) "\\" else "/"
    val trimmedRoot = cleanRoot.trimEnd('/', '\\')
    if (rel.isEmpty() || rel == ".") return trimmedRoot

    val segments = rel.split('/').filter { it.isNotBlank() && it != "." }
    if (segments.isEmpty()) return trimmedRoot
    if (segments.any { it == ".." }) return null

    return trimmedRoot + separator + segments.joinToString(separator)
}
