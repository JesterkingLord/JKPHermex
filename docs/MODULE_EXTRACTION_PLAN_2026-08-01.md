# Module Extraction Plan — JKPHermex → :jkp-* Gradle Modules

**Status:** Plan v1.0.0 (2026-07-30) · Author: JKP (Jester King Prime) · P5 of the Farouk Fusion super-program
**Branch:** will land on `feat/jkp-reliability-first-modernization` (or a new branch) — never on the main phone-app line without review.
**Regression floor:** 547 green tests + 18 skip. **Must not regress.**
**End state:** JKPHermex's app module is < 20 files; the 7 feature modules are independently `implementation(project(":jkp-..."))`-able for the super-app.

---

## 1. Why this matters

The super-program P3 (Farouk Fusion Super-App, v0.1.0 shipped today) needs the JKP control surface as a library, not as a separate APK. To make `:jkp-chat` reusable from `com.faroukfusion.app`, the JKPHermex code must be in a library module, not in a single 167-file `app` module.

Pattern A (the deep-link) is the v1.0 cross-app launch story. Pattern B (embedded) — which is the future "single super-app" UX — requires the modules to be consumable. P5 makes Pattern B possible.

---

## 2. Current state (before P5)

167 Kotlin files in **one** Gradle module: `:app`.

| Directory | Files | Imported by other dirs |
|---|---:|---:|
| `auth/` | 5 | 4 files (HermexApp, MainActivity, OnboardingViewModel, SessionCookieJar) |
| `config/` | 3 | 7 files |
| `model/` | 10 | 91 files (heavily cross-cutting) |
| `network/` | 21 | 90 files (heavily cross-cutting) |
| `persistence/` | 4 | (counted in feature deps) |
| `platform/` | 4 | (counted in feature deps) |
| `ui/` | 7 | (theme + reusable widgets) |
| `ui/theme/` | 1 | (HermexTheme.kt) |
| `ui/markdown/` | 4 | (markdown rendering) |
| `update/` | 1 | (ApkUpdater.kt) |
| `features/calendar/` | 2 | 0 (isolated) |
| `features/chat/` | 10 | 0 (isolated) |
| `features/composer/` | 4 | 0 (isolated) |
| `features/notes/` | 2 | 0 (isolated) |
| `features/onboarding/` | 2 | 0 (isolated) |
| `features/pairing/` | 2 | 0 (isolated) |
| `features/panels/` | 2 | 0 (isolated) |
| `features/prompts/` | 2 | 0 (isolated) |
| `features/sessionlist/` | 8 | 0 (isolated) |
| `features/settings/` | 2 | 0 (isolated) |
| `features/workspace/` | 2 | 0 (isolated) |
| **TOTAL** | **~95** | + top-level glue |

**Key finding:** `features/*` is **fully isolated** — 0 cross-imports between feature dirs. The natural module boundaries already exist in the directory layout.

---

## 3. Target module structure

9 modules total: 1 core + 7 feature modules (master plan) + 1 thin app.

| Module | Path | What | Why |
|---|---|---|---|
| **`:jkp-core`** | `android/lib/jkp-core/` | `model/`, `network/`, `persistence/`, `platform/` (minus widget), `ui/theme/`, `ui/markdown/`, `ui/AccentSwatch.kt`, `ui/DrawerPane.kt`, `ui/FastScrollbar.kt`, `ui/HermexComponents.kt`, `ui/JumpFab.kt`, `ui/MarkdownShare.kt`, `ui/PickerSheet.kt`, `config/`, **+ `auth/SecretStore.kt` + `auth/KeystoreSecretStore.kt`** (the secrets interface that `network` needs — cycle-break) | Cross-cutting foundation. Imported by everything. **Sinks all jkp-* deps** (no other jkp-* deps). |
| **`:jkp-auth`** | `android/lib/jkp-auth/` | `auth/AuthManager.kt`, `auth/PairingIntent.kt`, `auth/ServerUrlNormalizer.kt` (3 files) | The phone-auth features (pairing, token, server URL). Uses `:lib:jkp-core` for the network calls and model. |
| **`:jkp-chat`** | `android/lib/jkp-chat/` | `features/chat/` | The biggest feature (10 files). The one the super-app most needs. |
| **`:jkp-composer`** | `android/lib/jkp-composer/` | `features/composer/` | The input area (4 files). |
| **`:jkp-sessions`** | `android/lib/jkp-sessions/` | `features/sessionlist/` | Session list management (8 files). |
| **`:jkp-panels`** | `android/lib/jkp-panels/` | `features/panels/` | Side panels (2 files). |
| **`:jkp-settings`** | `android/lib/jkp-settings/` | `features/settings/` | Settings (2 files). |
| **`:jkp-workspace`** | `android/lib/jkp-workspace/` | `features/workspace/` | Workspace (2 files). |
| **`:app`** | `android/app/` (existing) | `MainActivity.kt`, `HermexApp.kt`, `SidebarRailCompact.kt`, `SidebarRailLayout.kt`, `features/onboarding/`, `features/pairing/`, `features/calendar/`, `features/notes/`, `features/prompts/`, `update/` | The thin app: nav + glue + the 5 smaller features (kept in-app because they're not in the super-program's P6 destinations). |

**Smaller features (calendar, notes, onboarding, pairing, prompts) stay in `:app`** because:
- They're not in the super-program's P6 destinations list (master plan §3.3: super-app ships with Home / JKP / Games / Library / Account)
- They're deeply integrated with `:app`'s nav graph
- Splitting them later is trivial once `:jkp-core` exists

**`update/` stays in `:app`** because the APK updater is app-specific.

---

## 4. Dependency graph (no cycles, after cycle-break)

**Real-world cycles that surface on audit:**
- `network/SessionCookieJar.kt` → `auth.SecretStore`
- `auth/AuthManager.kt` → `network.ApiClient`, `network.completePairing`, `network.SessionCookieJar`

A pure 1:1 module split isn't possible without breaking one of these. **Cycle-break chosen:** move `SecretStore` and `KeystoreSecretStore` (the 2 auth files network actually needs) into `:jkp-core`. They are a small interface + Android impl that the network layer needs; they don't belong in a "phone-auth" feature module anyway. The remaining 3 auth files (`AuthManager`, `PairingIntent`, `ServerUrlNormalizer`) stay in `:jkp-auth` and depend on `:jkp-core`. No cycle.

After the cycle-break:

```
:app
 ├── :jkp-auth          (AuthManager, PairingIntent, ServerUrlNormalizer)
 ├── :jkp-chat          (for Home destination)
 ├── :jkp-composer
 ├── :jkp-sessions
 ├── :jkp-panels
 ├── :jkp-settings      (for Settings destination)
 ├── :jkp-workspace
 └── :lib:jkp-core      (foundation: model, network, persistence, platform,
                         ui, config, AND the 2 secrets interfaces)

:lib:jkp-core
 (no jkp-* deps — only platform / AndroidX / Kotlin first-party)

:jkp-auth           → :lib:jkp-core
:jkp-composer       → :lib:jkp-core
:jkp-sessions       → :lib:jkp-core
:jkp-panels         → :lib:jkp-core
:jkp-workspace      → :lib:jkp-core
:jkp-settings       → :lib:jkp-auth, :lib:jkp-core
:jkp-chat           → :jkp-composer, :jkp-sessions, :jkp-panels, :lib:jkp-core
```

**No cycles. `:lib:jkp-core` is a sink.**

---

## 5. Execution order (10 steps, dependency-corrected)

**Correction from the original draft:** `:jkp-auth` depends on `:jkp-core` (`auth.AuthManager` imports `network.ApiClient`, `model.AuthStatusResponse`, etc.), so `:jkp-core` must come first. The original "start with :jkp-auth" plan was wrong.

| # | Step | Files moved | Tests affected | Risk | Time |
|---|---|---|---|---|---|
| 1 | **Create `:jkp-core`** | ~60 files (model, network, persistence, platform, ui/, config) | All 1109 tests | **HIGH** — touches everything | 1.5 days |
| 2 | Verify `:app` builds against `:jkp-core` | 0 moved | 1109 | regression check | 30 min |
| 3 | **Create `:jkp-auth`** | 5 files (auth/) | 98 auth + 4 importers | LOW | 2 hours |
| 4 | Verify | 0 moved | 1109 | regression check | 30 min |
| 5 | **Create `:jkp-composer`** | 4 files (features/composer/) | ~16 | LOW | 2 hours |
| 6 | **Create `:jkp-sessions`** | 8 files (features/sessionlist/) | ~130 | MEDIUM | 4 hours |
| 7 | **Create `:jkp-panels`** | 2 files (features/panels/) | ~5 | LOW | 1 hour |
| 8 | **Create `:jkp-workspace`** | 2 files (features/workspace/) | ~5 | LOW | 1 hour |
| 9 | **Create `:jkp-chat`** | 10 files (features/chat/) | ~172 | MEDIUM (depends on composer + sessions + panels) | 4 hours |
| 10 | **Create `:jkp-settings`** | 2 files (features/settings/) | ~5 | LOW | 1 hour |
| 11 | **Verify `:app` is < 20 files** | 0 moved | 1109 | regression check | 30 min |
| 12 | **Bump version 0.8.14 → 0.9.0** | CHANGELOG.md | n/a | n/a | 15 min |
| 13 | **Tag and release** | git tag | n/a | n/a | 15 min |

**Total: ~3-4 days** of focused work, with verification after every step.

---

## 6. Per-module structure (canonical)

Every `:jkp-*` module follows this shape:

```
android/lib/jkp-foo/
├── build.gradle.kts            # android library plugin; minSdk 26; namespace com.hermexapp.android.jkp.foo
├── consumer-rules.pro         # public ABI exposed to consumers
├── src/
│   ├── main/
│   │   ├── AndroidManifest.xml # empty <manifest>
│   │   └── kotlin/com/hermexapp/android/jkp/foo/    # (or auth/, config/, etc. for core)
│   │       └── *.kt
│   └── test/
│       └── kotlin/com/hermexapp/android/jkp/foo/    # JVM unit tests (no Android)
│           └── *.kt
```

The library uses the **same `libs.versions.toml`** as `:app` (no new versions). This is the v1.0.0 freeze: do not add new deps.

---

## 7. What the super-program can do after P5

```kotlin
// In super-app/build.gradle.kts
dependencies {
    implementation(project(":jkp-core"))     // theme, network, persistence, ui widgets
    implementation(project(":jkp-auth"))     // pairing + auth (pattern A target)
    implementation(project(":jkp-chat"))     // chat UI
    implementation(project(":jkp-composer")) // input area
    implementation(project(":jkp-sessions")) // session list
    implementation(project(":jkp-panels"))   // side panels
    implementation(project(":jkp-settings")) // settings screen
    implementation(project(":jkp-workspace"))// workspace
}
```

This is the "Pattern B (embedded mini-app)" story. It's not v1.0; it's a future pattern we keep open.

---

## 8. Risk mitigation

| Risk | Mitigation |
|---|---|
| Circular dependencies surface (e.g. `:jkp-chat` needs `:jkp-sessions` and vice versa) | The current import graph shows 0 cross-feature imports, so this is unlikely. If it happens, extract a `:jkp-shared` module with the common types. |
| Internal `com.hermexapp.android.*` packages leak into the public API | Use `consumer-rules.pro` to mark classes as `internal` for consumers. Add a lint check. |
| Gradle build time increases | Configure cache for the `:jkp-*` modules; reuse the configuration cache. |
| 547 tests break | Each step has a regression gate. If a step breaks tests, that step is reverted before proceeding. |
| Real device smoke test isn't possible in this Windows environment | The operator runs the manual smoke on their OPPO after each successful local build. |

---

## 9. Acceptance criteria

P5 is done when:
- All 1109 tests still pass (1 pre-existing failure documented; P5 must add zero new failures)
- `:app` has < 20 files (the thin glue)
- All 7 `:jkp-*` modules build independently
- `super-app/build.gradle.kts` can declare `implementation(project(":jkp-chat"))` and the import resolves
- Bumped version `0.9.0` is tagged
- CHANGELOG.md has the P5 entry

P5.6 (Pattern A on JKPHermex side) is done when:
- `faroukfusion://open-jkp` intent filter is in `AndroidManifest.xml`
- The deep-link routes to the chat destination
- All 547 tests still pass

---

## 10. What I am NOT doing in P5

- ❌ Not creating per-feature modules for `calendar`, `notes`, `onboarding`, `pairing`, `prompts` (not in the master plan; small; stay in `:app` for now)
- ❌ Not refactoring `model/` into per-feature DTOs (would be a separate effort)
- ❌ Not extracting `:jkp-shared` (only if a cycle surfaces; current graph is clean)
- ❌ Not changing any production code beyond moving files
- ❌ Not adding new dependencies to any module

---

## 11. Reference

- Master plan: `E:\MywebsiteFF\...\docs\FAROUK_FUSION_SUPER_PLATFORM_MASTER_ROADMAP.md` v1.2.0 §4 P5
- This file: `E:\JKPHermex\docs\MODULE_EXTRACTION_PLAN_2026-08-01.md` (v1.0.0)
- JKPHermex plan: `E:\JKPHermex\docs\PLAN_AND_ROADMAP.md`

---

I do not rule, I reveal.
