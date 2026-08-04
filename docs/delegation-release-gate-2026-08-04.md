# Delegation spec: JKPHermex v0.8.14 release gate (tests + lint + APK + docs)

You are working in the JKPHermex repo (`E:\JKPHermex`, branch
`feat/jkp-modular-extraction`). Android app: Kotlin 2.0.21 + Jetpack Compose
+ AGP 8.7.3, multi-module Gradle under `android/`. Current dev version
0.8.14 (versionCode 37). Latest stable line: v0.7.2-stable.

Current verified state: 584 unit tests green (fresh run), chat reliability
fixes just committed (session-scoped approvals, one-time scroll restore,
composer show/hide).

## Your job: close the release gate for the 0.8.14 development line

Work through these steps IN ORDER. Commit after each green step (atomic,
single-purpose messages). If a step fails, fix it; if you cannot fix it
within 2 attempts, stop at green and report the failure honestly.

### Step 1 — Full unit test suite (fresh)
```bash
export JAVA_HOME="/c/Users/roflm/AppData/Local/Programs/Microsoft/jdk-17.0.10.7-hotspot"
export TERM=dumb
cd /e/JKPHermex/android
./gradlew --console=plain testDebugUnitTest
```
Confirm 584+ tests, 0 failures. Report exact count from JUnit XML.

### Step 2 — Debug lint
```bash
cd /e/JKPHermex/android && ./gradlew --console=plain lintDebug
```
Must succeed. Record the warning categories (dependency-update notices,
cleartext opt-in, legacy compatibility, resource suggestions are known
acceptable warnings — do NOT chase those into changes).

### Step 3 — Release variant build + debug APK
```bash
cd /e/JKPHermex/android
./gradlew --console=plain assembleDebug
./gradlew --console=plain assembleRelease
```
`assembleRelease` may legitimately require signing config — if it fails on
signing only, that is a documented release-gate residual (store signing is
owner-gated); record the exact failure and move on. `assembleDebug` MUST
succeed. Report APK path + size.

### Step 4 — CHANGELOG + version check
- Read `CHANGELOG.md` and `android/app/build.gradle.kts`. If the 0.8.14
  session-scoped-approvals / scroll-restore / composer-toggle work is not
  yet in the changelog, add a dated 0.8.14 entry describing it in
  user-facing terms (one bullet per feature, no engineering jargon in
  user-visible lines; keep technical detail in the roadmap doc, not the
  changelog).
- Do NOT bump versionCode/versionName unless the changelog already
  anticipates it.

### Step 5 — Docs reconciliation
- `docs/PLAN_AND_ROADMAP.md`: update "Current state" to reflect that the
  session-scoped approval pass + scroll/composer fixes are shipped in
  source (commit 614c2c2), 584 tests green.
- Add a short "0.8.14 release gate evidence" section to
  `docs/PLAN_AND_ROADMAP.md` with: test count, lint result, APK build
  result, and the signing residual if applicable.

### Step 6 — Maestro static safety (no device)
```bash
cd /e/JKPHermex && python tools/verify_maestro_safety.py 2>/dev/null || ls maestro/
```
If the safety verification script exists, run it and record the result. If
it needs a device, record "device-gated" and skip. Do NOT run adb commands
that mutate a device.

## Hard rules

1. Do not invent test counts or build results — paste the real gradle
   output lines.
2. Do not modify product behavior in this pass (no feature work, no
   refactors). This is a verification + docs gate only. Exception: trivial
   lint fixes that are obviously safe AND keep all tests green.
3. Keep the tree clean at the end (`git status --short` empty except
   untracked artifacts).
4. Commit messages: `chore(release): <step> — <evidence>` pattern.

## Report (mandatory)

1. Step-by-step results with the actual command output lines (gradle
   summary lines).
2. Exact test count, lint verdict, APK path/size, release-build verdict.
3. Every commit SHA created.
4. Any residual that remains owner-gated (signing, device Maestro).
