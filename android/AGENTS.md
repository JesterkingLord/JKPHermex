# AGENTS.md — Android app (supplements the root AGENTS.md)

The Android client lives in this directory as a self-contained Gradle project.
The root `AGENTS.md` holds the platform-neutral rules (never invent endpoints;
tolerant decoding; don't commit broken builds); this file adds what's specific
to working on the Android app. `docs/ANDROID_PORT_PLAN.md` is the build plan —
implement phases in order unless the human says otherwise.

## Tooling
- Build and test from the repo root with the wrapper:
  `cd android && ./gradlew build` (assembles + runs unit tests + lint).
  Unit tests only: `./gradlew test`. Never commit `local.properties`.
- CI runs `./gradlew build` on Linux for every PR touching `android/**`,
  `shared/**`, or the upstream pin (`.github/workflows/android-ci.yml`).
- JDK 17+ required (the project targets Java 17; newer JDKs work).
- Emulator/manual testing: any API 26+ image; prefer the latest stable API.

## Dependencies (locked)
`gradle/libs.versions.toml` is the locked list — the Android analog of the
iOS locked list in `PROJECT_SPEC.md` §5: Compose (BOM), AndroidX core/activity/
lifecycle, kotlinx coroutines + serialization, OkHttp + okhttp-sse, and
JUnit + MockWebServer for tests. **Do NOT add other dependencies without asking.**

## Decoding rule (hard rule #3, Kotlin form)
Every wire model is a `@Serializable` data class with nullable, defaulted
fields, decoded through `Json { ignoreUnknownKeys = true }`.
`TolerantDecodingTest` pins this configuration; endpoint contract tests read
the shared corpus in `shared/fixtures/` (see `SharedContractWiringTest`) and
the pin in the root `UPSTREAM_TESTED_SHA`. Never hand-write fixture JSON.

## App identity (provisional)
`applicationId` / namespace: `com.hermexapp.android`, version `0.1.0`.
The id is provisional until the first Play upload (port plan §6 open
question) — changing it later is a one-line edit before release, impossible
after.
