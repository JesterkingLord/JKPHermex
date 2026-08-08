package com.hermexapp.android.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Workspace-browser and read-only git shapes mirroring the iOS Workspace.swift /
// GitWorkspace.swift (verified upstream: api/workspace.py, api/workspace_git.py).

/** `GET /api/list?session_id=…&path=…`. */
@Serializable
data class DirectoryListResponse(
    val entries: List<WorkspaceEntry>? = null,
    val path: String? = null,
    /**
     * The workspace root — **absent on the deployed host, so usually null.**
     *
     * `.codex-tmp/hermes-webui` emits this key, but that tree is not what runs:
     * the server answering :8787 is `E:/JKP/hermes-webui/server.py`, roughly
     * 1,750 lines behind, and its list handler returns only `entries`,
     * `signature` and `path`. Probed live against both a WebUI-native and a
     * CLI-backed session; neither carried the key.
     *
     * Kept rather than deleted, because the newer tree does send it and it will
     * decode once the host is updated. Treat it as a bonus, never a source:
     * anything needing an absolute path must read `workspace` off the session,
     * which every host sends. `/api/media` rejects a relative path with 403, so
     * guessing from here costs a round trip that can only fail.
     */
    val workspace: String? = null,
    val error: String? = null,
)

@Serializable
data class WorkspaceEntry(
    val name: String? = null,
    val path: String? = null,
    /** `"dir"`, `"file"` or `"symlink"`. */
    val type: String? = null,
    /**
     * Only sent for symlinks, and it is `is_dir` — not `is_directory`, which
     * the server never sends. A regular entry carries no such flag at all; its
     * kind is in [type]. The previous `@SerialName("is_directory")` therefore
     * decoded to null every time, which made a symlink pointing at a directory
     * unbrowsable (its own type is "symlink", so the type fallback said no).
     */
    @SerialName("is_dir") val isDir: Boolean? = null,
    val size: Long? = null,
    /** Nanosecond mtime; the newest-first sort the file views want. */
    @SerialName("mtime_ns") val mtimeNs: Long? = null,
    /** Symlink destination, absent for entries that escape the workspace. */
    val target: String? = null,
    /**
     * True when a symlink points outside the workspace. The server
     * deliberately withholds the target, size and resolved kind for these, and
     * refuses to read through them, so the UI must not offer navigation.
     */
    @SerialName("target_outside_workspace") val targetOutsideWorkspace: Boolean? = null,
) {
    val isBrowsableDirectory: Boolean
        get() = targetOutsideWorkspace != true && (isDir == true || type == "dir")

    val stableId: String get() = path ?: name ?: "entry-${hashCode()}"
}

/** `GET /api/file?session_id=…&path=…`. */
@Serializable
data class FileResponse(
    val content: String? = null,
    val path: String? = null,
    val name: String? = null,
    val language: String? = null,
    val size: Long? = null,
    val lines: Int? = null,
    val error: String? = null,
)

/** `GET /api/git-info?session_id=…` — `git` is null for a non-repo workspace. */
@Serializable
data class GitInfoResponse(val git: GitInfo? = null)

@Serializable
data class GitInfo(
    val branch: String? = null,
    val dirty: Int? = null,
    val ahead: Int? = null,
    val behind: Int? = null,
    @SerialName("is_git") val isGit: Boolean? = null,
)

/** `GET /api/git/status?session_id=…`. */
@Serializable
data class GitStatusResponse(val git: GitStatus? = null)

@Serializable
data class GitStatus(
    @SerialName("is_git") val isGit: Boolean? = null,
    val branch: String? = null,
    val upstream: String? = null,
    val ahead: Int? = null,
    val behind: Int? = null,
    val totals: GitTotals? = null,
    val files: List<GitFile>? = null,
    val truncated: Boolean? = null,
)

@Serializable
data class GitTotals(
    val changed: Int? = null,
    val staged: Int? = null,
    val unstaged: Int? = null,
    val untracked: Int? = null,
    val conflicts: Int? = null,
)

@Serializable
data class GitFile(
    val path: String? = null,
    @SerialName("old_path") val oldPath: String? = null,
    val status: String? = null,
    val staged: Boolean? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
) {
    val stableId: String get() = path ?: oldPath ?: "file-${hashCode()}"
}

/** `GET /api/git/branches?session_id=…`. */
@Serializable
data class GitBranchesResponse(val branches: GitBranches? = null)

@Serializable
data class GitBranches(
    @SerialName("is_git") val isGit: Boolean? = null,
    val current: String? = null,
    val detached: Boolean? = null,
    val local: List<GitBranchRef>? = null,
    val remote: List<GitBranchRef>? = null,
    val ahead: Int? = null,
    val behind: Int? = null,
)

@Serializable
data class GitBranchRef(
    val name: String? = null,
    val sha: String? = null,
)

/** `GET /api/git/diff?session_id=…&path=…&kind=…`. */
@Serializable
data class GitDiffResponse(val diff: GitDiff? = null)

@Serializable
data class GitDiff(
    val path: String? = null,
    val kind: String? = null,
    val binary: Boolean? = null,
    @SerialName("too_large") val tooLarge: Boolean? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val diff: String? = null,
)
