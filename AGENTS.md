# AGENTS.md — working agreement for Hermex

Hermex is a set of native mobile clients for a self-hosted `hermes-webui` server:
the shipping SwiftUI iPhone app lives under `ios/`, and a Kotlin/Compose Android app
is planned under `android/` (see `docs/ANDROID_PORT_PLAN.md`). `PROJECT_SPEC.md` is
the product/API source of truth — if a request conflicts with it, stop and ask.
Read by every agent (Codex, Claude Code, …); keep it tool-agnostic.

This file holds only the platform-neutral rules. Platform tooling and identity live
in the directory you're working in: `ios/AGENTS.md` today, `android/AGENTS.md` once
that app exists. Read the platform file before touching code in that directory.

## Session start & wrap-up
- Read **`docs/PLAN_AND_ROADMAP.md`** for version roadmap (0.5.0 baseline → 0.6.0 shipped 2026-07-21).
- Read `CURRENT.md` first if it exists — it holds the latest resumable state. It is
  local-only (gitignored), never committed; a fresh clone won't have one.
- Read only the `PROJECT_SPEC.md` sections named in CURRENT.md's **Spec Read** field;
  never the whole ~850-line spec unless told to.
- Active work lives in GitHub Issues. Implement only the issue the human selects, one
  labeled `ready-for-agent`, or one named in CURRENT.md — not every open issue.
- On "wrap up": verify repo/build/test state, overwrite `CURRENT.md` with the new
  state (it stays uncommitted), then commit the code.
  History lives in `git log` and merged PRs; there is no append-only log.

## How work flows
- One issue → one short `issue/<n>-slug` branch → one PR (branches with no issue use
  `chore/` or `fix/`). Issue/triage/domain conventions live in `docs/agents/`.
- `master` is the protected release-candidate branch (the source for internal
  TestFlight builds): keep it buildable — for every app in the repo — and never do
  feature work on it.
- Pushing a branch, opening/updating a PR, or merging needs explicit human approval.
  Triage bot/review comments before accepting them.

## Hard rules
1. **Never invent API endpoints or JSON shapes.** Verify in this precedence order:
   (a) `curl` your own running server — final arbiter; (b) the official API docs at
   https://get-hermes.ai/api-docs/ — best for endpoint intent, auth contract, SSE
   event vocabulary, and conventions (no version pin; tracks the latest release);
   (c) the pinned upstream copy at `.codex-tmp/hermes-webui/api/routes.py` — ground
   truth for exact JSON shapes, but may lag the release the docs describe (clone it
   if missing: `git clone https://github.com/nesquena/hermes-webui .codex-tmp/hermes-webui`).
   That upstream copy is read-only — never modify it (refreshing via `git pull` is fine).
2. **No new third-party dependencies** beyond each platform's locked list without approval.
3. **Tolerant decoding:** every decoded model (Swift `Codable` today; Kotlin
   serialization later) uses optionals/nullables for fields upstream might
   add/rename. Never crash on unknown fields.
4. **No destructive commands** (`rm -rf`, `git push --force`, anything touching
   `~/Library/LaunchAgents/` or restarting Mac services). Suggest them; let the human run them.
5. **Don't commit broken builds.** If a build or test fails, fix it before writing more code.

## Working with the human
- Surface tradeoffs in plain English before non-obvious choices; when in doubt, ask.
- Ask before touching anything under the spec's "Open questions."
- After each slice, report: (1) files changed (2) build/test command run (3) result
  (4) next suggested step — plus a short manual simulator/emulator test plan when UI changed.

## Keep this file honest
If something here surprises you or contradicts the project, tell the developer and
**propose** an AGENTS.md edit — don't silently edit it. This file is a Band-Aid for what
can't be fixed in code/tests/tooling; your proposed edits are also a signal of what to fix structurally.
