# JKPHermex Excellence v1 — UX & Quality-of-Life Enhancement Plan

**Date:** 2026-07-23  
**Author:** JKP Assistant (post v0.6.2-stable grey-band fix)  
**Target:** `master` branch, shippable in 4–6 waves, ≤ 2 weeks, no backend changes  
**Status:** ✅ Plan written. ⏸ Awaiting operator review before any code changes land.

---

## 0. TL;DR — what you're getting

You asked for an app that feels like **ChatGPT-class polished**:

> "Add a scroll function on the side in the JKPhermex app for chat sessions so we can quickly scroll up and down, also add feature to delete sessions and more features that is user friendly and quality of life enhancing in the app."

That maps to 6 user-facing capabilities and several hidden bug fixes. I'll ship them in **6 waves**. Each wave ships behind its own version tag (v0.7.0 → v0.7.6). The first wave is the visible-from-day-one milestone; later waves layer polish, edge cases, and pro-grade touches.

| Wave | Tag | Headline | Lines changed (≈) |
|------|-----|---------|--------------------|
| 0 | `v0.7.0` | **Delete with Undo** (snackbar pattern) + multi-select bulk ops + new "select all / clear all" toolbar | 800 |
| 1 | `v0.7.1` | **Fast scroll for session list** + first-character jump (Gmail-style) | 400 |
| 2 | `v0.7.2` | **Chat timeline scrollbar + jump-to-top/bottom + scroll position memory** | 600 |
| 3 | `v0.7.3` | **Message-level actions** (copy/edit-and-resend/regenerate/share, polished) | 700 |
| 4 | `v0.7.4` | **In-chat search** (find within a session, jump to match, ↑↓ between matches) | 800 |
| 5 | `v0.7.5` | **Pro polish pass** (animations, empty states, focus, a11y, multi-window) | 600 |

### Status

| Wave | Status | Tag |
|------|--------|-----|
| 0 | ✅ SHIPPED | `v0.7.0-stable` (commit `0967844`) |
| 1 | ✅ SHIPPED | `v0.7.1-stable` (commit `be2e629`) |
| 2 | ✅ SHIPPED | `v0.7.2-stable` (commit `f58ca96`) |
| 3 | ✅ SHIPPED | `v0.7.3-stable` (commit `8ebd757`) |
| 4 | ✅ SHIPPED | `v0.7.4-stable` (commit `9b1cf2c`) |
| 5 | ✅ SHIPPED — partial (5.1 + 5.2; **5.3/5.4/5.5/5.6 deferred**) | `v0.7.5-stable` (commit pending — see Wave 5 §7 below) |

**Important guardrails:**

- **No backend changes.** Every endpoint I touch already exists (`/api/session/delete`, `/api/session/rename`, `/api/session/pin`, `/api/session/archive`, `/api/session/branch`, `/api/session/duplicate`, `/api/session/move`). I'm not inventing new HTTP.
- **No new third-party deps** unless the operator OKs it. (Fast-scroll-letter-jump and M3 `pullToRefresh`/`Snackbar` come from `material3` already on the classpath — no `coil`/`accompanist`/newer compose-bom.)
- **Every wave ends with** a full test re-run (currently 327/327 → expect 340+ by end), a `git tag` cut, APK rebuild, and a one-paragraph status note to you before the next wave starts.
- **If a slice goes wrong or you don't like the design**, the next-version rollback is `git checkout <prev-tag>` — everything is local changes.

---

## 1. Source-of-truth: what's already in v0.6.2

This is what I confirmed reading the current `master @ ac34763`:

| Surface | Already wired | Gap |
|---|---|---|
| **Session delete** | `ApiClient.deleteSession` → `SessionListViewModel.deleteSession(id)` → `SessionRow` actions dialog + swipe-left → confirm. *(Screenshots in v0.6.2 still show band — unrelated bug.)* | No undo, no bulk delete, no snackbar feedback, silent failure on error. |
| **Session archive** | Per-row via swipe-right + dialog | Same gap. No bulk archive. |
| **Pin / Rename / Move / Duplicate / Fork** | All wired in `SessionActionsDialog` | No bulk versions. Move-to-project shows "No project" sentinel but no header indicating which project the session currently belongs to. |
| **Search** | `OutlinedTextField` + 300ms debounce → `/api/sessions/search?q=` | No highlighted matches. No keyboard "Search" button → triggers search immediately. |
| **Chat timeline scroll** | `LazyColumn` + `animateScrollToItem(lastIndex)` on new entry | No scrollbar. No jump-to-top/bottom FAB. No scroll-position save across navigations. No jump-to-message via deep link. |
| **Chat composer** | Text field (5 lines max), attachments strip, slash suggestions, model/reasoning/workspace/profile pickers | No "stop" button mid-run visibility. No "draft saved" indicator if back-press. No submit-on-Enter support. No quick-clear-all. |
| **Message actions** | Long-press assistant message → Copy/Listen/Regenerate | No edit-and-resend for user messages. No "share to clipboard as Markdown". No "quote reply". No "jump to original" from a forked message. |
| **In-chat search** | None | — |
| **Settings** | Theme, accent, expand defaults, notifications, default model, headers, sign-out, about | No "Clear cache" button. No "Reset to defaults". No "Confirm before delete" toggle. |
| **Connection errors** | `ClientErrorCatalog` translates to user-facing strings | No retry button surfaces in the chat composer (only in session list). |
| **Empty / loading states** | Session list has three: loading, empty, no-match. Chat has loading + empty (no messages yet). | No "disconnected from server" empty state with retry, no "refresh" pull gesture. |
| **Animations** | `animateItem()` on session list | No shared-element transition between session list ↔ chat. No entry-typing indicator animation on assistant messages (only on send). No completion haptic on certain actions. |

That's the audit. Every wave below addresses a column from the "Gap" list.

---

## 2. Wave 0 — `v0.7.0` Delete with Undo + Bulk Select

### Goal

Make session management feel native (ChatGPT / Gmail / Apple Mail tier). Currently: delete works, but it's a one-shot destructive action with no feedback. After this wave: every delete is reversible for 5 seconds; long-press enters a multi-select mode where you can delete/archive/pin N sessions at once.

### Slices (each is one PR-sized commit)

**Slice 0.1 — `SessionListViewModel`: snackbar event channel**
Add `val events: SharedFlow<SessionListEvent>` next to `uiState`. Define a sealed class:
```kotlin
sealed interface SessionListEvent {
    data class Deleted(val ids: List<String>, val titles: List<String>) : SessionListEvent
    data class Restored(val ids: List<String>) : SessionListEvent
    data class Archived(val ids: List<String>, val archived: Boolean) : SessionListEvent
    data class Pinned(val ids: List<String>, val pinned: Boolean) : SessionListEvent
    data class Error(val message: String) : SessionListEvent
}
```
`deleteSession/...` now emits to `events` instead of mutating `errorMessage`. `errorMessage` stays for non-actionable errors (network down on initial load).

**Slice 0.2 — Snackbar host in `SessionListScreen`**
Wrap the `LazyColumn` body in a `Scaffold(snackbarHost = { SnackbarHost(it) })`. Collect `events` via `LaunchedEffect`, call `snackbarHostState.showSnackbar(...)` with `"Deleted 'X'. UNDO"` action label. On UNDO action, call `repository.restoreSession(id)` — that's a NEW repo call. (See Slice 0.4.)

**Slice 0.3 — `DeleteSessionResponse` restore-only contract**
Server doesn't have a "restore" endpoint, and adding one would be a backend change we said no to. **Solution:** delete via `POST /api/session/delete` is the only delete primitive. Once deleted, gone. BUT — we can implement "soft delete with 5-second undo window" purely client-side:
- On delete, the VM remembers the session payloads (`SessionSummary` + last known position)
- UNDO calls `repository.createSession(seed = deleted)` — **we don't have a "seed" endpoint either.**
- **Pragmatic decision:** UNDO on a single delete is achievable by re-creating an empty session with the same title, then warning the user "Restored as new chat (history not recovered)". Documented honestly in the snackbar copy. For multi-delete, UNDO is not offered — instead we offer a single-shot "Undo last bulk delete" that re-creates N empty chats with the originals' titles. The history IS lost. This is the same pattern Apple Mail uses.
- **Alternative: don't offer UNDO on hard-deletes that lose history.** Show "Deleted. This action cannot be undone." in the snackbar (ChatGPT-style). Cleaner, no false promise.

I'll implement the **cleaner alternative** as default, with a small preference toggle in Settings: "Offer undo for destructive actions" (default OFF, because the server is the truth and we don't have a restore primitive). Operator can decide.

**Slice 0.4 — Multi-select mode**
- `var selectionMode by remember { mutableStateOf(false) }`
- `var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }`
- Long-press a row → enter selection mode with that one row selected (don't open the action dialog anymore).
- Top of `SessionListScreen` swaps the title row for a contextual action bar:
  - "Cancel" (left)
  - "N selected" (middle)
  - "Select all" / "Deselect all" (right of N)
  - Below: icon row with [Pin, Archive, Delete] — taps apply to all selected sessions, optimistic update with refresh on success.
- Tap a row in selection mode → toggle selection. Tap-and-hold another row → adds to selection.
- "Delete N" pulls one AlertDialog: "Delete N sessions? This cannot be undone." Cancel + Delete (destructive color).
- Auto-exit selection mode when N → 0.
- Swipe gestures DISABLED while in selection mode.

**Slice 0.5 — Error retry button in chat composer**
Plus: surface `state.errorMessage` from `ChatViewModel` in the composer area as a non-blocking banner with a "Retry" button when the message is "aborted mid-run" or similar retriable. (Was asked separately in the user's message.)

### Test coverage (this wave's safety net)

Tests added/touched:
- `SessionListViewModelTest` — extend with: delete emits `Deleted` event; selection set membership add/remove; select-all selects everything visible
- `ChatViewModelTest` — errorMessage appears + clears on retry
- New: `BulkSelectionFlowTest` (instrumented would be nicer but pure-JVM first)

Total after this wave: ~333 tests, 0 failures expected.

### Exit criteria

1. Long-press a row → multi-select mode opens, top action bar visible with N=1.
2. Long-press another row → N=2; "Pin all" → both get pinned server-side + list refreshes.
3. "Delete all" → confirm dialog → snackbar "Deleted 2 sessions. This cannot be undone." (5s, no undo button because per design).
4. Swipe gestures don't fire in selection mode (test, not just inspect).
5. Tests green. APK builds. versionName 0.7.0, versionCode 16.

---

## 3. Wave 1 — `v0.7.1` Fast scroll for session list

### Goal

A 30+ session list is currently just a scrollable vertical list. After this: there's an **alphabet jump bar** (Gmail-style) on the right edge, with thumb + section letters, and a first-character quick-jump (tap a row when scrolled to ~50%, press a letter, list jumps to first title starting with that letter).

### Slices

**Slice 1.1 — `LazyListState.firstVisibleItemIndex` exposed already; build a derived `letterIndex: Map<Char, Int>` from `state.sessions` titles.**
Cache it inside the composable (not in the VM) — derivable in O(N) and recomputed when sessions change.

**Slice 1.2 — `FastScrollbar` composable**
A thin custom composable (no new deps) at `ui/FastScrollbar.kt`. Renders:
- A 12dp-wide transparent hit zone covering the right edge of the parent
- A thumb dot (4dp wide, 32dp tall) tinted with `palette.accent`, follows `listState.firstVisibleItemIndex / itemCount`
- Optional section-letter labels at hover position (only when actively scrolling)
- Gesture: drag → instant `listState.scrollToItem(letterIndex[letter])` or `scrollToItem(index)`

**Slice 1.3 — `LazyColumn` instrumentation**
Add `firstVisibleItemIndex` and `firstVisibleItemScrollOffset` to a `derivedStateOf` so the FastScrollbar rebuilds only when needed.

**Slice 1.4 — Disable fast scroll when list fits viewport**
`if (state.sessions.size < 20) return` — don't show the thumb for short lists (no UX value, takes screen space).

### Tests

- `FastScrollbarTest` — given a list of N items and a synthetic `LazyListState` at index k, the thumb renders at the correct y-offset.
- Skip instrumented tests for the gesture (Compose UI testing is a new infra dep — out of scope unless operator demands).

### Exit criteria

1. With >20 sessions, dragging the right edge = fast scroll.
2. With ≤20 sessions, no thumb visible.
3. No regression on existing scroll.

---

## 4. Wave 2 — `v0.7.2` Chat timeline scrollbar + jump-to-top/bottom + position memory

### Goal

Long chat? Right now you have to swipe to the top to find an old message. After this:
- **Right-edge scrollbar thumb** (same `FastScrollbar` composable as wave 1, reused)
- **Scroll-to-top/bottom FAB cluster** (a single Material floating button that toggles between ⤴ and ⤵ based on scroll direction; tap → animated scroll; long-press alternative is the ChatGPT pattern where two buttons stack)
- **Scroll position memory** — remember `firstVisibleItemIndex` + `firstVisibleItemScrollOffset` keyed by session ID, persisted via `AppPrefs`. When the user opens a chat and immediately leaves + returns, they land where they were.

### Slices

**Slice 2.1 — Reuse `FastScrollbar` for chat** by passing the chat's `listState`. No new composable code.

**Slice 2.2 — `JumpFab` composable**
Lives in `ui/`. A small `FloatingActionButton` that:
- Shows when `listState.firstVisibleItemIndex > 5` from bottom (i.e., user has scrolled away from bottom)
- Direction: ⤴ if scrolled up, ⤵ if scrolled down past last item
- Tap → `listState.animateScrollToItem(if down) 0 else state.entries.lastIndex`
- Auto-fades when within 4 items of either edge (no need to scroll)

**Slice 2.3 — `AppPrefs.scrollPosition(sessionId: String): ScrollPosition?` getter/setter**
- JSON-encoded small struct `{ firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int }`
- Backed by DataStore or SharedPreferences (whatever's already in `AppPrefs.kt`)

**Slice 2.4 — `ChatViewModel.saveScrollPosition()` / `loadScrollPosition()`**
Called from `DisposableEffect(onDispose) { viewModel.saveScrollPosition(listState) }` and from `LaunchedEffect(Unit) { viewModel.loadScrollPosition(); listState.scrollToItem(...) }`. Round-trip while the session is loading — feel native.

**Slice 2.5 — Scroll-position save ONLY on real exit**
Don't save on every recompose. Save when:
- The user leaves the chat screen (Back press → via DisposableEffect + isAttached check), OR
- 500ms after the last scroll, debounced (so we don't store mid-scroll jank positions).

### Tests

- `ChatViewModelScrollPositionTest` — save → load round-trip returns same values for same session id
- Skip FAB layout tests (UI test infra)

### Exit criteria

1. Open a 50-message chat, scroll halfway down, leave to session list, reopen → land at same position (visual diff: hard to prove without UI test infra; assert the round-trip in unit tests).
2. Long chat shows the JumpFab after scrolling >5 items from bottom.
3. Scrollbar thumb visible only on long chats.

---

## 5. Wave 3 — `v0.7.3` Message-level actions

### Goal

Power-user feature parity. Every message gets a coherent action menu; the user message bubble gets edit-and-resend (currently we only have regenerate for the assistant).

### Slices

**Slice 3.1 — User message: long-press → "Edit & resend" / "Copy" / "Delete" dialog**
- "Edit & resend" pre-fills the composer with the message text + bumps the conversation: previous assistant reply remains visible above (so context isn't lost) + a new user message appears below with the edited text + new assistant response. (This is how ChatGPT does it.)
- Implementation: rather than re-sending via `/api/chat/start`, we POST a new message into the existing session — server should handle that, or this becomes a server-side slice (note: this may require `/api/chat/start` to accept `edit_of: message_id` parameter, which **would** be a backend change). **Fallback:** regenerate the assistant response by truncating after that message and re-sending. ChatGPT does this client-side too.

**Slice 3.2 — Assistant message: keep existing Copy/Listen/Regenerate, ADD "Copy as Markdown" + "Quote reply"**
- "Copy as Markdown" formats the assistant turn into markdown with code fences preserved (use `Markdown.kt` extractor to convert rendered → source).
- "Quote reply" inserts `> quoted text\n\n` prefix into the composer (works for both user and assistant messages — keep consistent).

**Slice 3.3 — Streaming assistant message: "Stop typing" UX**
Currently we have the ⬛ stop button in the composer. Add a pulsing cursor indicator on the streaming assistant message itself (▍ already there — make it shimmer with `infiniteRepeatable` animation).

**Slice 3.4 — Multi-message selection (stretch)**
Long-press any message enters a "select messages" mode where you can tap to select multiple, then "Copy N messages" merges them into a clipboard-friendly format. Skip if timeline cramped — gate on session-list feasibility first.

### Tests

- Edit-and-resend: `ChatViewModelTest` extended with `_redoUserMessage(oldId, newText)` returning the new stream
- Quote reply: pure UI state, skip unit test

### Exit criteria

1. Long-press a user message → menu with "Edit & resend" + "Copy" + "Delete".
2. Edit-and-resend produces a new user message + new assistant reply; original pair grayed out.
3. Assistant message has the new actions.

---

## 6. Wave 4 — `v0.7.4` In-chat search

### Goal

Find a specific message in a long chat. ChatGPT/Gemini have this. Standard pattern: tap a search icon in the chat header (next to the back button), modal sheet opens at top, type to search, matches highlight in the timeline with ↑/↓ nav, "X of N" indicator, close to dismiss.

### Slices

**Slice 4.1 — Local search (no backend)**
Compose-side `LazyListState` + structural search through `state.entries`. For each `UserMessage`/`AssistantMessage`/`Reasoning` entry, check if `text.contains(query, ignoreCase = true)`. Skip tool calls (too noisy).

**Slice 4.2 — Search bar UI**
A persistent (but collapsible) row that slides down from the top of the chat header when activated. `OutlinedTextField` + match count + ↑ ↓ buttons + close. Sticks at the top of the LazyColumn area so matches scroll into view as you navigate.

**Slice 4.3 — Highlight match in entry rendering**
`MarkdownText` already renders `entry.text`. Add a `Modifier.highlightMatches(query)` extension that wraps matches in a styled span (background = accent with low alpha). Falls back to inline styling.

**Slice 4.4 — Server-side search (optional, only if N > 100)**
`/api/sessions/search` searches session list but **not** within a single session. Within-session search is composer-side only. Document this in the help docs.

### Tests

- Pure function: `findMatchesInEntries(entries, query) -> List<Int>` — pure JVM, testable.

### Exit criteria

1. Tap search icon → bar slides in, keyboard auto-focus.
2. Type a known phrase that exists in the visible entries → first match auto-highlighted and scrolled into view.
3. ↑/↓ navigate between matches; counter updates.

---

## 7. Wave 5 — `v0.7.5` Pro polish pass

### Goal

Ship the difference between "good app" and "premium app". Things users feel but don't consciously notice.

### Slices

**Slice 5.1 — Empty / disconnected states**
- Session list: "Disconnected" empty state with "Retry" button (different from the existing error banner — this is for when `errorMessage` stays for >3s).
- Chat: "No network" overlay if `!isStreaming && errorMessage == "no connection"` — full-screen scrim with retry CTA.
- Composer empty draft warning: if user attempts to send empty text, don't silently drop — show a 1-second "Type something first" toast.

**Slice 5.2 — Smart auto-scroll behavior**
- If user has scrolled up to read old messages, a new assistant token arriving shouldn't yank them down. Pause auto-scroll IF `listState.firstVisibleItemIndex < state.entries.lastIndex - 2`. Show a subtle "↓ N new messages" pill at the bottom — tap to jump down + resume auto-scroll.
- (This is the single biggest "feels like ChatGPT" win — it's how ChatGPT behaves, and it's not implemented here yet.)

**Slice 5.3 — Accessibility / focus**
- All interactive elements get `Modifier.semantics { contentDescription = ... }`.
- TalkBack order verified.
- Min touch target 48dp enforced.

**Slice 5.4 — Haptics polish**
- Confirmation haptic on long-press action selection (already partially there — extend to new actions).
- Light impact on send.
- Subtle tap on regenerate.

**Slice 5.5 — Animation polish**
- `animateContentSize()` on chat composer as attachments add/remove.
- `AnimatedVisibility` for the new contextual action bar (slide from top + fade).
- 200ms crossfade on theme change instead of instant flash.

**Slice 5.6 — Multi-window / large screen**
- Tablet/foldable users get a two-pane layout (session list left, chat right) when `WindowSizeClass` is `WidthSizeClass.Expanded`. This is the ChatGPT / Gmail-on-tablet pattern. Hooks into existing `MainActivity.setContent { ... }` via `calculateWindowSizeClass()`. **Server-side unchanged** (this is just layout).

### Tests

- New: `AutoScrollGuardTest` — given listState at index k and a new entry, the auto-scroll fires only if k is near the bottom.
- Skip the rest (UI / a11y tests are infrastructure-heavy).

### Exit criteria

1. Send an empty composer → toast, no silent fail.
2. Scroll up while streaming → no auto-yank; "↓ N new" pill appears.
3. Tablet layout shows two panes.

---

## 8. What I'm explicitly NOT shipping in this scope

For honesty's sake (avoiding over-promising):

| Skipped | Why |
|---|---|
| True message-level **restore** after delete (re-attach full history) | Requires `/api/session/restore` backend. Per design constraint, no new endpoints. UNDO is offered only by recreating empty shells (operator can opt out). |
| AI-suggested titles for new chats | Already implemented (host feature `auto_title`). |
| Voice-mode whole-conversation dictation | Voice input per-message already exists. |
| Image generation attachment / preview | Backend capability not in this version. |
| Push-notification grouping per-session | Out of UI scope (the platform/RunNotifications layer handles notification firing, not grouping). |
| Native M3 PullToRefresh | Requires compose-material3 1.3.x min, currently on whatever BOM is. Will check; if cheap, add, otherwise defer. |

---

## 9. Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| LazyListState + bulk concurrent updates cause flicker on session list | low | I check during slice 0.4 — if so, debounce selection updates by 50ms |
| Scroll position memory breaks if messages get inserted server-side mid-session | medium | On session load, if saved index > current entries.lastIndex, clamp to lastIndex |
| Material 3 Snackbar action doesn't surface on some OEM ROMs | low | Tested via emulator only — accept risk, document |
| `animateItem()` + scroll-position restore together flicker | medium | Restore scroll BEFORE LazyColumn sees its items (use `LaunchedEffect(listState)` once when first batch arrives) |
| Compose fast-scroll thumb conflicts with edge swipe in gesture nav | low | Detect edge-inset and add 8dp safe zone |
| Operator wants a feature removed mid-wave | medium | Waves ship independently. `git checkout <prev-tag>` rolls back cleanly. |
| Time budget overrun | medium | Each wave is independently shippable; we can ship wave 0 + 1 first, ship the rest over time. |

---

## 10. Workflow / my proposed cadence

- **One slice per round-trip with you.** I don't batch slices silently.
- Each slice ends with: (a) `git add && git commit -m "feat(session): ..."`, (b) `gradlew testDebugUnitTest` re-run, (c) APK rebuild, (d) I update you with a 3-bullet status: what changed, what's verified, what's next.
- **Tag every wave**, not every slice (less tag noise). v0.7.0 (wave 0), v0.7.1 (wave 1), etc.
- **Branch off `master` for each wave** (`feat/wave-7.0-delete-undo`) and merge to master when green. OR push directly to master if you prefer (your preference; default: feature branch → PR-style merge with tests green).
- If you'd rather I do **all 6 waves back-to-back** in one mega-PR: I can. Just say so.

---

## 11. Out of scope / pending operator decisions

These need you before I write code:

1. **Bulk delete UNDO design** — offered as "Recreate as empty chat" (truthful, no fake promise) OR not offered at all (ChatGPT clean). Default: not offered. OK?
2. **Operator-only-secret features** — should there be a "Master unlock" 4-digit code in Settings that gates destructive bulk operations (like Apple ID biometric)? Skip by default unless you say so.
3. **Multi-pane layout on tablets** — enabled by default, or opt-in via Settings? (I default: enabled when device is expanded; else single-pane.)
4. **Edit-and-resend flow** — when user edits a mid-history user message and resubmits, do we:
   - (a) **Roll back everything after it** (ChatGPT Edge) → cleaner but server may not support (would need `truncate_after: msg_id`).
   - (b) **Append as new turn** (Gmail reply) → simpler, no server change, but the chat becomes fork-y.
   - Default: (b). OK?
5. **FastScrollbar thumb color** — use `palette.accent` always, or add `palette.scrollbar` token and let it differ in light mode? Default: accent.
6. **Wave ordering** — I propose 0→1→2→3→4→5 (i.e. fast-scroll BEFORE chat scrollbar because the same composable is used). If you'd rather have chat scrollbar first (because it's the headline ask), I'll swap 1 and 2.

---

## 12. Files touched (anticipated, by wave)

Cumulative count by end of all 6 waves (estimate, ±15%):

| Tier | Files | Reason |
|---|---|---|
| New | ~12 | `FastScrollbar.kt`, `JumpFab.kt`, `SessionListEvent.kt`, `BulkSessionActionsBar.kt`, `MatchedMessage.kt`, `Modifier.highlightMatches.kt`, plus tests |
| Heavily edited | ~8 | `SessionListScreen.kt`, `ChatScreen.kt`, `ChatViewModel.kt`, `SessionListViewModel.kt`, `ComposerControls.kt`, `SessionRow.kt` (extracted from inline), `AppPrefs.kt`, `MainActivity.kt` |
| Small edits | ~10 | colors, strings, icon imports, layout files |
| Touch only | ~5 | Theme tokens, build.gradle.kts (versionCode), AndroidManifest (any new strings) |

Total: ~35 files. Half-wave major, half-wave minor. Manageable.

---

## 13. Next step

**Awaiting your answers to §11 (operator decisions, especially #1, #3, #4) and your go/no-go on the overall plan.**

I'll wait for your message before writing a single line of code. Default fallback if you don't reply with overrides: §11 defaults stand, waves 0→1→2→3→4→5 execute in order, feature-branch-per-wave, slice-by-slice commits, status update per slice.

—

When you're ready, I can also:

1. **Convert this plan into GitHub Issues** (one per wave + one per slice) for you to track. I have the `github-issues` skill loaded and can `gh issue create --body` for each.
2. **Save the operator decisions** as memory entries so future sessions remember your preferences (e.g. "operator prefers chat scrollbar before session fast-scroll", "operator OK with no-undo on delete").
3. **Set up a `cron job` that pings me weekly** to keep momentum on the remaining waves if we get interrupted.

Just say the word.
