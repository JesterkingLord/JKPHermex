# JKPHermex Session Quality, Rename, Slash Commands, Prompt Library Polish & Reader Mode — 2026-08-04

**Branch:** `feat/jkp-modular-extraction`
**Spec source:** `E:/JKPHermex/PROJECT_SPEC.md` (verify), `E:/JKPHermex/.codex-tmp/hermes-webui` (ground truth for HTTP)
**AGENTS.md rules:** (1) never invent endpoints — verify; (2) no new 3rd-party deps; (3) tolerant decoding; (4) no destructive commands; (5) don't commit broken builds.
**Acceptance baseline:** `gradlew --no-daemon testDebugUnitTest testReleaseUnitTest` reports 581 tests passing, lintDebug clean.

---

## Status — 2026-08-05

**All six slices are implemented and the tree is verified green**:
641 tests / 0 failures / 82 suites, lint 0 errors, 20.4 MB debug APK,
installed and launched on the CPH2343.

The 2026-08-04 attempt could not verify any of it. Its log blamed a Gradle
daemon crash; the real cause was a test-suite hang, diagnosed by thread dump:
the poll tests left `startBackgroundRefresh()`'s unbounded
`while (isActive) { delay(...) }` loop running whenever an assertion failed
before `stopBackgroundRefresh()`, and `runTest`'s drain then advanced virtual
time through it forever. Fixed in `67dc930`.

| Slice | State |
|---|---|
| 1 — slash dispatch (`/steer` `/queue` `/interrupt` `/title` `/status` `/help`) | Implemented, unit-tested. **Not yet exercised against a live run.** |
| 2 — streaming send-behaviour preference | Implemented, unit-tested. Device check pending. |
| 3 — full-screen reader mode | Implemented + **fresh-install bug fixed** (`75a3fc7`): the pref's polarity was inverted, hiding the composer on every new install. Composer presence verified on device. |
| 4 — session-list background refresh + streaming dot | Implemented. **Plus the actual "stuck" fix** (`67dc930`): resuming now refreshes immediately and re-arms the cadence, rather than waiting out an interval scheduled at the old background rate. |
| 5 — chat status chips | Implemented, unit-tested. Device check pending. |
| 6 — prompt library polish | Implemented. **Extended** (`009a149`) with the piece that was actually missing: prompts you *sent* are now captured and offered for saving, instead of the library only holding what was typed into it by hand. |

Three fixes are committed (`67dc930`, `75a3fc7`, `009a149`). The six slices
themselves remain uncommitted in the working tree.

---

## TL;DR — what already exists vs what's missing

| Feature | API/Storage | UI / wiring | Action |
|---|---|---|---|
| Session rename | `Endpoint.SESSION_RENAME`, `ApiClient.renameSession` (lib/jkp-core/.../network/ApiClientSessions.kt:54), `SessionRepository.renameSession` (lib/jkp-sessions/.../SessionRepository.kt:49), `SessionListViewModel.renameSession` (lib/jkp-sessions/.../SessionListViewModel.kt:202) | `RenameDialog` + action menu (lib/jkp-sessions/.../SessionListScreen.kt:824, :531, :755) | **Polish only**: long-press the row → action sheet must surface "Rename"; add in-list rename icon on tap-and-hold. Currently no keyboard auto-focus, no `errorMessage` rollback snackbar on rename failure. |
| Prompt library CRUD | `PromptsStore` (lib/jkp-core/.../persistence/PromptsStore.kt:94) + Room + InMemory, `PromptsDao` upsert/delete/setPinned/bumpUsage | `PromptsScreen` with `PromptEditor`, FAB "New prompt", swipe-to-delete with UNDO, search, bulk delete (app/src/main/.../features/prompts/PromptsScreen.kt:147) | **Polish**: tap a row to open the editor (currently you must swipe or use FAB). Add an explicit edit pencil on row. Editor should auto-focus the name field. |
| `/api/chat/steer` | `Endpoint.CHAT_STEER` (lib/jkp-core/.../network/Endpoints.kt:33), `ApiClient.steerChat` (lib/jkp-core/.../network/ApiClientChat.kt:56), `ChatViewModel.steerNow` (lib/jkp-chat/.../features/chat/ChatViewModel.kt:625). Upstream `_handle_chat_steer` at .codex-tmp/hermes-webui/api/streaming.py:11552, dispatched at .codex-tmp/hermes-webui/api/routes.py:15339. | Composer only calls `steerNow` when streaming + draft present; no slash parsing | **Add slash parsing**: `/steer`, `/queue`, `/interrupt`, `/title` (rename), `/status`, `/help` (mirror iOS SlashCommandCatalog). |
| `/api/chat/queue` | **NO HTTP ROUTE** upstream. `queue_message` exists as a runner-protocol method (api/runner_client.py:94, api/runtime_adapter.py:289) but not exposed via HTTP. | n/a | **Implement client-side queue** (iOS pattern): an in-memory list that auto-sends after the current run completes. Document the limitation. |
| Composer hide / full-screen reader | `composerVisible` state in ChatScreen.kt:118, `onHideComposer` callback in ComposerControls.kt:102, `AnimatedVisibility` at line 447 | Works, but collapsed composer leaves wasted vertical space at bottom | **Add true reader mode**: when hidden, expand the transcript to the bottom inset, hide the latest-arrow pill, dim the title bar chrome, keep only a single floating "Show composer" button. Persist preference. |
| "Stuck" sessions | No automatic session list polling (only manual pull-to-refresh + after-navigation refresh). `SessionListViewModel.refresh()` at line 110 is the only entry point. `ChatViewModel.onSseEvent` handles SSE errors but no "last seen" indicator. `HangHonesty.tipIfStalled` (lib/jkp-chat/.../features/chat/HangHonesty.kt) shows tips only when `isStreaming == true`. | n/a | **Add session-state indicator** on the chat screen + **background session-list refresh** (15s while visible, 60s otherwise). |
| Long-press send | `onLongPressSend` callback wired at MainActivity (app/src/main/.../MainActivity.kt:561) → `insertSheetOpen = true` (opens an insert sheet) | Not a queue | **Add a "Send behavior" picker** (steer / queue / interrupt) in Settings. Make the long-press button honor that preference. iOS already exposes this via `StreamingSendBehavior.storageKey` (ios/HermesMobile/Features/Chat/ChatView.swift:55). |

---

## Hard constraints recap

- **No invented endpoints.** All HTTP calls go through `ApiClient` and use `Endpoint.*` enum values that mirror upstream `.codex-tmp/hermes-webui/api/routes.py`.
- **No new 3rd-party deps.**
- **Tolerant decoding** — every new `data class` field is nullable with a default.
- **48 dp touch targets** (already a rule; see CURRENT.md "Enforced 48 dp custom navigation rows").
- **Preserve untracked `_disabled_calendar/`, `prompts and notes/`, `screenshots/`** — don't commit them.
- **Don't commit broken builds.** Run `gradlew testDebugUnitTest testReleaseUnitTest lintDebug assembleDebug` before declaring done.

---

## Slice 1 — Slash command dispatch in the chat composer

**Goal:** typing `/steer fix the bug`, `/queue <msg>`, `/interrupt <msg>`, `/title New name`, `/status`, `/help` triggers the right action. Mirror iOS SlashCommandCatalog.swift.

### 1a. Extend `ComposerConfig` to expose `/`-prefixed command descriptors

File: `android/lib/jkp-chat/src/main/java/com/hermexapp/android/features/chat/ComposerConfig.kt`

- Add `data class SlashCommandDescriptor(name: String, description: String, argHint: String, handler: SlashHandler)`.
- Add `enum class SlashHandler { STEER, QUEUE, INTERRUPT, RENAME, STATUS, HELP }`.
- The existing `slashSuggestions(draft)` already filters server-provided `AgentCommand`s. Add a `localSlashCommands(): List<SlashCommandDescriptor>` that returns the static local set so they appear in the picker even before `/api/commands` returns.
- Hard rule: only `/`-prefixed drafts (line 44) trigger suggestions. Anything else is plain text.

### 1b. Add a dispatcher in `ChatViewModel`

File: `android/lib/jkp-chat/src/main/java/com/hermexapp/android/features/chat/ChatViewModel.kt`

- Add `fun submitDraft()` — replaces `send()` for the send button. Inspects `_uiState.value.composerText`; if it starts with `/` and parses to a known local slash, dispatch and clear the composer; otherwise call `send()`.
- Implement handlers:
  - `/steer <text>` → if `isStreaming` → call `client.steerChat(sessionId, text)`; on `{accepted:false}` → enqueue client-side + call `stopNow()` and snackbar "Steer was unavailable; queued for next turn". If not streaming → call `send()` with the text after the command (same as iOS).
  - `/queue <text>` → if `isStreaming` → enqueue; if not → call `send()` with text.
  - `/interrupt <text>` → if `isStreaming` → enqueue + `stopNow()`; if not → call `send()` with text.
  - `/title <new title>` → call `repository.renameSession(sessionId, title)`; on success refresh title; on failure surface `errorMessage`.
  - `/status` → append a `TimelineEntry.Notice` with a compact one-line status (model, profile, workspace, queued count, streaming y/n).
  - `/help` → append a `TimelineEntry.Notice` listing all local slash commands.
- Add `data class QueuedMessage(text: String, attachments: List<PendingAttachment>)` and a `queuedMessages: List<QueuedMessage>` field on `UiState`. Drain it on `SseEvent.Done` and `SseEvent.Cancelled` — if non-empty, call `send()` with the head.
- Server-routed commands (e.g. `/branch`, `/compact`) still go through `startChat` exactly as today; the local dispatcher only short-circuits client-routed ones.

### 1c. Wire send button to new entry point

File: `android/lib/jkp-chat/src/main/java/com/hermexapp/android/features/chat/ChatScreen.kt` (call site at ComposerBar send button) — change the lambda from `viewModel::send` to `{ viewModel.submitDraft() }`. Keep `onLongPressSend` calling the streaming behavior picker (slice 2).

### 1d. Tests
File: `android/lib/jkp-chat/src/test/java/com/hermexapp/android/features/chat/SlashCommandDispatchTest.kt` (new)

- `/steer fix bug` while streaming → calls `client.steerChat(sessionId, "fix bug")` exactly once, clears composer, does NOT call `startChat`.
- `/steer fix bug` while idle → calls `startChat` with message `"fix bug"`.
- `/queue msg` while streaming → appends to `queuedMessages`, no network.
- `/queue msg` while idle → calls `startChat` with `"msg"`.
- `/interrupt msg` while streaming → cancels stream + enqueues msg.
- `/title New name` → calls `repository.renameSession(sessionId, "New name")`.
- `/status` while idle → appends a `Notice` with "Streaming: No", not a network call.
- Plain text → calls `startChat` with the literal text.
- Drain on `Done` event: queued message becomes the next user message.

---

## Slice 2 — Streaming send behavior preference

**Goal:** persistent operator choice of what long-press-send does. iOS has `StreamingSendBehavior { steer, interrupt, queue }` with `@AppStorage`. Add the same.

### 2a. AppPrefs entry

File: `android/lib/jkp-core/src/main/java/com/hermexapp/android/config/AppPrefs.kt`

- Add `fun streamingSendBehavior(): StreamingSendBehavior` and `fun setStreamingSendBehavior(value: StreamingSendBehavior)`.
- Add `enum class StreamingSendBehavior { STEER, INTERRUPT, QUEUE }` with stable string keys `"steer" / "interrupt" / "queue"`.
- Default `STEER` to match iOS line 55.

### 2b. Honor it in ChatViewModel

File: `android/lib/jkp-chat/src/main/java/com/hermexapp/android/features/chat/ChatViewModel.kt`

- New `fun submitLongPressDraft()` that reads `prefs?.streamingSendBehavior()` and routes the composer text to `steerNow`, `interruptNow`, or `enqueueMessage` accordingly. When the chosen handler is unavailable (e.g. `/steer` server rejected, falling back to interrupt+queue), fall through to the next behavior in the order steer → interrupt → queue → drop.

### 2c. Settings entry

File: `android/lib/jkp-settings/src/main/java/com/hermexapp/android/features/settings/SettingsScreen.kt`

- New row: "Long-press send" → opens a 3-radio picker that calls `viewModel.setStreamingSendBehavior(value)`.

### 2d. Tests
- `AppPrefsStreamingSendBehaviorTest` in `android/lib/jkp-core/src/test/.../config/`.
- `StreamingSendBehaviorDispatchTest` in `android/lib/jkp-chat/src/test/.../features/chat/`.

---

## Slice 3 — True full-screen reader mode

**Goal:** hiding the composer feels like reading mode, not like "composer collapsed".

### 3a. Persist preference

File: `android/lib/jkp-core/src/main/java/com/hermexapp/android/config/AppPrefs.kt`

- Add `readerMode: Boolean` (default false), `setReaderMode(value: Boolean)`.

### 3b. Reader mode visual treatment

File: `android/lib/jkp-chat/src/main/java/com/hermexapp/android/features/chat/ChatScreen.kt`

- When `composerVisible == false`, the LazyColumn fills the bottom inset; hide the title-bar chrome (or keep just the session title centered, no menu button); suppress the JumpFab / latest-arrow pill.
- A single floating circular "Show composer" FAB in the bottom-right (48 dp) restores `composerVisible = true` and persists the change.
- The existing `composerVisible` local state is one-shot; promote it to `AppPrefs.readerMode` so reopening the chat respects the choice.

### 3c. Tests
- `ReaderModePersistenceTest` in `android/lib/jkp-chat/src/test/.../features/chat/`.

---

## Slice 4 — Session-list "stuck" UX fix (background refresh + indicator)

**Goal:** the session list feels live; chats don't feel frozen.

### 4a. Background polling with visibility-aware cadence

File: `android/lib/jkp-sessions/src/main/java/com/hermexapp/android/features/sessionlist/SessionListViewModel.kt`

- Add `startBackgroundRefresh()` that launches a coroutine on `viewModelScope`:
  - Every 15s while the screen is `STARTED` (use `ProcessLifecycleOwner.lifecycle` observer in the screen, not the VM).
  - Every 60s while in background.
  - Stops on `viewModelScope` cancellation.
- Wire start/stop from `SessionListScreen` via `DisposableEffect` keyed to the lifecycle.
- Respect power-saving: skip the 15s tick if `BatteryManager.isCharging == false && PowerSaveMode == true`. Backoff to 60s.

### 4b. Live indicator dot on each session row

File: `android/lib/jkp-sessions/src/main/java/com/hermexapp/android/features/sessionlist/SessionListScreen.kt`

- When `session.isStreaming == true`, show a small green pulsing dot next to the title (8 dp).
- When `session.pinned == true`, show a pin icon (existing behavior, keep).
- Test: `SessionRowStreamingDotTest`.

### 4c. Pull-to-refresh feedback
Already exists (`PullToRefreshBox`). Ensure it shows the spinner during the 15s polling tick too, not only on manual pull.

---

## Slice 5 — Chat screen "stuck" UX fix (real-time status chip)

**Goal:** when the agent isn't emitting tokens, the user sees why (thinking, tool running, connection drop, stream pending).

### 5a. New `lastSeenAtMs` on UiState

File: `android/lib/jkp-chat/src/main/java/com/hermexapp/android/features/chat/ChatViewModel.kt`

- Add `val lastSeenAtMs: Long? = null` — updated by `markStreamActivity()` on every SSE event.
- When `isStreaming && now - lastSeenAtMs > 30s` and no `HangHonesty.tip` set yet, surface a non-modal chip "Working…" with a small animated dot (different copy from `HangHonesty.tipIfStalled` which uses honest "we may be slow" copy).

### 5b. Connection chip

- When `SseEvent.TransportError` fires, show "Reconnecting…" with an inline retry counter (use `reconnectAttemptCount`).
- Already-true `HangHonesty.tipIfStalled` covers the long-stall case; this just adds a faster visual.

### 5c. Stream-end / done feedback

- When `SseEvent.Done` arrives, briefly show a "Reply ready" chip for 2 seconds (animates fade-out), so users don't miss the completion if they're scrolled up.

### 5d. Tests
- `ChatStreamStatusChipTest` in `android/lib/jkp-chat/src/test/.../features/chat/`.

---

## Slice 6 — Prompt library UX polish

**Goal:** discoverable edit UX + auto-focus name field + better empty-state copy.

### 6a. Tap-to-edit

File: `android/app/src/main/java/com/hermexapp/android/features/prompts/PromptsScreen.kt` (`PromptsList` composable, around line 193)

- Whole row tap → opens `PromptEditor` for that id. Use a 48 dp tap target. Keep swipe-to-delete and long-press selection.

### 6b. Editor polish

File: `android/app/src/main/java/com/hermexapp/android/features/prompts/PromptsScreen.kt` (`PromptEditor` composable, around line 236)

- Auto-focus the name field on open using `FocusRequester`.
- Pre-existing inline upserts are good; add a "Save & close" affordance for users who don't want autosave-every-keystroke. Save on explicit Save button + auto-close the editor.

### 6c. Empty-state copy

- "No prompts yet — tap **New prompt** to save a template you send often."

### 6d. Tests
- Already has `PromptsViewModelTest`, `PromptTagsTest`. Add `PromptsScreenTapToEditTest`.

---

## Files expected to change

```
android/lib/jkp-chat/src/main/java/com/hermexapp/android/features/chat/ComposerConfig.kt   [+]
android/lib/jkp-chat/src/main/java/com/hermexapp/android/features/chat/ChatViewModel.kt     [++]
android/lib/jkp-chat/src/main/java/com/hermexapp/android/features/chat/ChatScreen.kt        [+++]
android/lib/jkp-chat/src/main/java/com/hermexapp/android/features/chat/ComposerControls.kt   [+]
android/lib/jkp-chat/src/test/java/com/hermexapp/android/features/chat/SlashCommandDispatchTest.kt   [NEW]
android/lib/jkp-chat/src/test/java/com/hermexapp/android/features/chat/StreamingSendBehaviorDispatchTest.kt   [NEW]
android/lib/jkp-chat/src/test/java/com/hermexapp/android/features/chat/ReaderModePersistenceTest.kt   [NEW]
android/lib/jkp-chat/src/test/java/com/hermexapp/android/features/chat/ChatStreamStatusChipTest.kt   [NEW]
android/lib/jkp-core/src/main/java/com/hermexapp/android/config/AppPrefs.kt              [+++]
android/lib/jkp-core/src/test/java/com/hermexapp/android/config/AppPrefsStreamingSendBehaviorTest.kt   [NEW]
android/lib/jkp-sessions/src/main/java/com/hermexapp/android/features/sessionlist/SessionListViewModel.kt   [++]
android/lib/jkp-sessions/src/main/java/com/hermexapp/android/features/sessionlist/SessionListScreen.kt    [+++]
android/lib/jkp-sessions/src/test/java/com/hermexapp/android/features/sessionlist/SessionRowStreamingDotTest.kt   [NEW]
android/lib/jkp-settings/src/main/java/com/hermexapp/android/features/settings/SettingsScreen.kt   [+]
android/app/src/main/java/com/hermexapp/android/features/prompts/PromptsScreen.kt         [++]
android/app/src/test/java/com/hermexapp/android/features/prompts/PromptsScreenTapToEditTest.kt   [NEW]
android/app/src/main/java/com/hermexapp/android/MainActivity.kt                          (no change; ChatViewModel handles everything)
```

No new dependencies. No new permissions. No manifest changes.

---

## Build & test commands (must all pass)

```
cd E:/JKPHermex/android
./gradlew --no-daemon testDebugUnitTest
./gradlew --no-daemon testReleaseUnitTest
./gradlew --no-daemon lintDebug
./gradlew --no-daemon assembleDebug
```

Expected outcome: 581 + new tests, all green; lint 0 errors.

---

## CURRENT.md update

After all slices ship, overwrite `E:/JKPHermex/CURRENT.md` with the new state (it stays uncommitted).

---

## Out of scope (per operator memory)

- **Web/website changes** — never my lane.
- **iOS parity edits** — read-only reference.
- **Backend additions** — server is the source of truth; Android must conform.
- **Destructive commands** — `./gradlew clean`, `git push --force`, anything restarting services — never run.

---

## Risk register

- **R1**: long-press-send colliding with text-selection gesture on the composer. Mitigation: only register long-press on the send circle, not the text field.
- **R2**: background polling may drain battery. Mitigation: power-save mode fallback (slice 4a) + lifecycle observer.
- **R3**: client-side queue may surprise users (server has no native queue). Mitigation: clear snackbar "Queued for next turn (#N)" mirrors iOS.
- **R4**: persistence schema change for `readerMode` in AppPrefs must not break existing installs. Mitigation: nullable getter with default-false.

---

## Verification plan (manual)

1. Cold-start app, open an existing chat. Confirm 581 tests still pass and the chat renders identically.
2. In the chat composer type `/status` then send. Confirm a notice appears with model/profile/workspace info and no `/api/chat/start` fires.
3. Start a long-running task. While it's streaming, type `/steer remember to also check Y`. Confirm a user message is appended and the server `steer` endpoint is called.
4. Same scenario but type `/queue check Z after this`. Confirm a "Queued for next turn (#1)" snackbar + the queued chip appears.
5. Hide the composer via the existing chevron. Confirm full-screen reader mode (transcript fills bottom, title chrome dims). Reopen via the FAB. Restart app → preference persists.
6. Long-press the send button → confirm it honors the Settings preference (steer/queue/interrupt). Cycle the preference and re-test.
7. Open the prompts screen. Tap an existing prompt → editor opens with the name field focused. Edit + Save → confirms Round-trip works.
8. Leave the session list idle for 60 seconds; confirm the list refreshes and pinned/streaming badges are visible.
9. Tap ⋮ on a session row → Rename → edit title → confirm the dialog auto-focuses the title field and the rename persists after restart.
