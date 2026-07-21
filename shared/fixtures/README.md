# shared/fixtures — cross-platform contract fixtures

Recorded JSON payloads from a real `hermes-webui` server (the commit pinned in the
root `UPSTREAM_TESTED_SHA`), shared by every client's contract tests so all apps are
verified against one wire format.

Status: **empty on purpose.** Today the iOS suite's contract fixtures are inline in
Swift test code (`ios/HermesMobileTests/`, e.g. `APIEndpointContractTests.swift` and
the `URLProtocol` mock server). Payloads get recorded here — one file per
endpoint/scenario, named `<endpoint>__<scenario>.json` — when the Android app's
contract tests need them (Android port plan §5, `docs/ANDROID_PORT_PLAN.md`), and
the iOS tests migrate to reading the same files opportunistically.

Rules:

- Fixtures are **recorded, never hand-written** — capture them from a running server
  (`scripts/webui-json`) or copy verbatim from the pinned upstream source. Hard rule
  #1 (never invent JSON shapes) applies here with full force.
- Re-record when `UPSTREAM_TESTED_SHA` advances; a pin-advance PR that changes
  fixtures must run every client's contract tests (see `CONTRACT_TESTS.md`).
- Scrub anything private (hostnames, session content, tokens) before committing.
