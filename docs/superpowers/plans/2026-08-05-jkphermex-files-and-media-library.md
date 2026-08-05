# JKPHermex Files & Media Library — 2026-08-05

**Branch:** `feat/jkp-modular-extraction`
**Requested:** a library of every image, video, document and file created or
uploaded through JKP — synced from the PC, split into "created" and
"uploaded", better organised than the ChatGPT app's.

**AGENTS.md rules that bind this plan:** never invent endpoints — verify
against `.codex-tmp/hermes-webui`; no new third-party dependencies; tolerant
decoding (every new field nullable with a default); don't commit broken builds.

---

## 1. Endpoint research (verified, not assumed)

Read from `.codex-tmp/hermes-webui/api/routes.py` on 2026-08-05.

| Need | Endpoint | Verified contract |
|---|---|---|
| Enumerate files | `GET /api/list?session_id=<id>&path=<rel>` | `routes.py:13239` → `_handle_list_dir` (`:16940`). Returns `{entries, signature, path, workspace, workspace_recovered}`. Lists **within one session's workspace**; `session_id` is required. |
| Entry shape | — | `workspace.py:1332`+ → `name`, `path`, `type`, `is_dir`, `mtime_ns`, and `size` on regular entries. Escape symlinks deliberately omit target/size. |
| Render an image/video | `GET /api/media?path=<absolute>` | `routes.py:13437` → `_handle_media` (`:18878`). Serves **one file by absolute path**. Auth-gated. Path must resolve inside an allowed root: `HERMES_HOME`, `/tmp`, `~/.hermes`, the active workspace, plus anything in `MEDIA_ALLOWED_ROOTS`. SVG always downloads (XSS). |
| Read a file | `GET /api/file` | already wired: `Endpoint.FILE` |
| Raw bytes | `GET /api/file/raw` | `routes.py:13441` |
| Download a folder | `GET /api/folder/download` | `routes.py:13446` |
| Upload | `POST /api/upload`, `POST /api/workspace/upload` | `Endpoint.UPLOAD` already wired; `routes.py:13978` |
| Workspaces | `GET /api/workspaces` | already wired: `Endpoint.WORKSPACES` |

### What does not exist upstream

- **No media index.** `/api/media` fetches one path; there is no "list all
  images" route. Enumeration must go through `/api/list` per directory.
- **No provenance.** Nothing records whether a file was produced by a tool/MCP
  or uploaded by the operator. `/api/list` returns filesystem entries only.
  **The requested "created vs uploaded" split cannot be read from the server.**
  See §3 — this is the one part of the request that needs a design decision
  rather than an implementation.
- **No cross-session file view.** `session_id` is mandatory on `/api/list`, so
  "everything ever created" means walking sessions, not one call.

---

## 2. What this makes buildable now

A **Files** destination in the drawer, beside Notes and Prompts:

- **Browse** the active session's workspace via `/api/list`, with breadcrumb
  navigation and a parent-directory row.
- **Media grid** — filter to images/video by extension, thumbnailed through
  `/api/media?path=<absolute>`; the workspace absolute path comes back in the
  `/api/list` response, so paths are constructed from server data, not guessed.
- **Type filters** — Images / Video / Documents / Code / All, decided by
  extension on the client (the server does not classify).
- **Sort** by name or `mtime_ns` (newest first is the useful default).
- **Open** — images/video inline; documents through the existing file reader;
  anything else offers download.
- **Upload** into the current directory via `/api/workspace/upload`, so the
  "uploaded" half is real rather than inferred.

All of that rests on endpoints confirmed above.

---

## 3. The "created vs uploaded" split — decision required

The server cannot answer this. Three options, in order of preference:

1. **Client-side provenance ledger (recommended).** The app already knows when
   *it* uploads a file. Record `(workspace, relative path, uploadedAt)` in a
   local store — same shape as `SentPromptsStore` — and treat everything else
   in the workspace as "produced here". Honest, cheap, no server change, and
   correct for anything the operator uploads from the phone. Its limit must be
   stated in the UI: files uploaded from the PC are not distinguishable and
   will read as created.
2. **Heuristic by directory.** Treat a conventional folder (e.g. `uploads/`) as
   uploaded. Fast, but invents a rule the rest of the system does not enforce —
   it will be wrong quietly.
3. **Server-side provenance.** Correct and complete, but it is a backend change
   and the backend is the source of truth this app conforms to. Out of scope
   here; worth raising separately if the split matters more than the browsing.

**Recommendation: option 1**, with the section labelled "Uploaded from this
device" rather than "Uploaded" — a label the app can actually stand behind.

---

## 4. Slices

### Slice 1 — contract + repository
- `Endpoint.LIST("/api/list")` (new), reusing `FILE`, `MEDIA`, `UPLOAD`.
- `WorkspaceEntry` DTO: `name`, `path`, `type`, `isDir`, `sizeBytes?`,
  `mtimeNs?`, `targetOutsideWorkspace?` — all nullable with defaults.
- `FilesRepository.list(sessionId, path)` returning the honest
  Loading/Ready/Failed states this app already uses.
- Tests: tolerant decoding of a real-shaped payload, a symlink row missing
  size/target, and a 404 mapping to a typed failure.

### Slice 2 — Files screen (browse)
- Drawer entry; breadcrumb; parent row; 48 dp targets; empty and error states.
- Tests: breadcrumb segmentation, parent-path derivation, sort comparators.

### Slice 3 — media grid + inline preview
- Extension classifier (pure, tested) → Images / Video / Documents / Code.
- Thumbnails via `/api/media`; SVG deliberately excluded from inline preview,
  matching the server's own rule.

### Slice 4 — upload + provenance ledger
- Upload into the current directory; record it in the local ledger.
- "Uploaded from this device" section derived from the ledger.
- Tests: ledger round-trip, dedupe, and that a file absent from the ledger is
  never labelled uploaded.

### Slice 5 — cross-session view (optional, after 1–4 land)
- "Recent files" across the sessions already cached locally, since `/api/list`
  is per session. Cost: N calls. Gate behind explicit refresh, not a poll.

---

## 5. Explicitly not promised

- **"Synced from PC" is read-through, not sync.** The app reads the host's
  workspace live over HTTP; nothing is mirrored or cached to the device beyond
  what is opened. Calling it "synced" would overstate it.
- Files outside the allowed roots (`HERMES_HOME`, `/tmp`, `~/.hermes`, active
  workspace, `MEDIA_ALLOWED_ROOTS`) are **not reachable** — the server refuses
  them by design, and the app must not appear to offer them.
- No thumbnail generation for video: the server serves the file, and decoding a
  frame client-side is out of scope for slice 3.

---

## 6. Gate

```
cd E:/JKPHermex/android
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Baseline to preserve: 641 tests, 0 failures, lint 0 errors.

Reminder from the last pass: any test that starts an unbounded loop on
`viewModelScope` must stop it in a `finally`, and drain with `runCurrent()`
rather than `advanceUntilIdle()`.
