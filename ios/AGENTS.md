# AGENTS.md — iOS app (supplements the root AGENTS.md)

Everything Apple lives in this directory: the `HermesMobile` app target, share
extension, Live Activity widget, tests, xcconfigs, and TestFlight export options.
The root `AGENTS.md` holds the platform-neutral rules; this file adds what's
specific to working on the iOS app. Run all commands below from the repo root.

## Tooling
- The maintainer works in **VS Code**, not the Xcode UI — prefer terminal validation;
  ask to open Xcode only when the terminal can't answer.
- Use **XcodeBuildMCP** for simulator build/test/run/log; fall back to raw
  `xcodebuild`/`xcrun simctl` for release/archive or low-level diagnosis. Defaults live
  in `.xcodebuildmcp/config.yaml` at the repo root (project
  `ios/HermesMobile.xcodeproj`, scheme `HermesMobile`, sim **iPhone 17**); if that
  sim is missing, pick a nearby iPhone and say which.
- **Simulator installs must be signed.** Never install a `CODE_SIGNING_ALLOWED=NO`
  build on the simulator for manual testing — that flag is for compile-only checks
  (see `TESTFLIGHT.md`) and strips entitlements, so Keychain writes fail with
  `errSecMissingEntitlement` and login breaks. Put the app on the sim via XcodeBuildMCP
  `build_run_sim` or a plain signed Debug build (no signing-disabling flags), then install/launch.
- Before asking for review or committing a slice: run the full XCTest suite, and
  build + launch the app for the human's manual simulator test when UI changed.

## App identity (resolved via xcconfig — not grep-able)
Bundle ID `com.uzairansar.hermesmobile` · tests `….tests` · Team `6GYD9C9N6R` · SKU `hermes-mobile-ios`.

## "push to branch testflight" (maintainer-only)
Upload the current branch to the side-by-side **Hermex Branch** internal TestFlight app
(`com.uzairansar.hermesmobile.branch`) — a TestFlight upload, **not** a git push.
Requires the maintainer's App Store Connect access; contributors never need this. Use a
unique `CURRENT_PROJECT_VERSION` (e.g. `YYYYMMDDHHMM`) each time. Full commands + branch
identity: `DEVELOPMENT.md`. Never touch the production `com.uzairansar.hermesmobile` app
unless explicitly asked.
