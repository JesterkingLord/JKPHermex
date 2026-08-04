# Delegation spec: JKP README + PROJECT_SPEC refresh

You are working in the JKPHermex repo (`E:\JKPHermex`, branch
`feat/jkp-modular-extraction`). The 0.8.14 line shipped 2026-08-04
(commits `614c2c2`, `cb953c2`, `1882079`, `c194dc5`). What needs
updating is the public-facing docs so a new contributor (or the operator
returning later) understands the current state.

## Read first

- `README.md`
- `PROJECT_SPEC.md`
- `PROJECT_INTENT.md`
- `docs/PLAN_AND_ROADMAP.md` (already updated by the release-gate agent)
- `CHANGELOG.md` (already updated)
- `docs/V0814_DEVICE_SMOKE_CHECKLIST.md` (new in this iteration)

## What to build

### 1. README refresh
- Update the version table to **0.8.14** (currently showing the older
  baseline; verify against `android/app/build.gradle.kts`).
- Add a "Latest evidence" section that links the canonical gate
  artifacts: `docs/PLAN_AND_ROADMAP.md` "0.8.14 release gate evidence"
  bullet, `docs/V0814_DEVICE_SMOKE_CHECKLIST.md`, and
  `CHANGELOG.md`.
- Verify the development-build command is correct for the current
  module layout.

### 2. PROJECT_SPEC refresh
- Update the "Current Status" / "Latest shipped" line.
- Replace any TODO markers that are actually shipped.
- Confirm the contract references are unchanged: the app_role /
  app_installation_id fields live in the host pairing contract v1.1.0
  — do not touch the contract body.

### 3. CHANGELOG trim
- The CHANGELOG already has the 2026-08-04 v0.8.14 entries. If the
  docs added another sub-entry, keep the format consistent.

## Hard rules

1. NO product-behavior changes. Docs only.
2. NO version bump (stays 0.8.14).
3. Stage ONLY files you create/modify (explicit paths).

## Report

Files touched, commit SHA, a brief before/after diff summary of what
changed.