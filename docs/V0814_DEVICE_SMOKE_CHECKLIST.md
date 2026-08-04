# v0.8.14 — Device smoke checklist

Manual, evidence-based checks for the connected-phone pass. This document is
the operator's run sheet — no `adb` command in this file is executed by an
agent; a human runs these checks on a real device with the app installed and
paired to a live JKP host. Prereqs: JKPHermex `0.8.14` (or newer) debug APK
installed, phone on the same Tailscale/Wi-Fi network as the host, paired.

| # | Check | Expected evidence |
|---|---|---|
| 1 | Open a real session; the chat scrollbar is draggable. | Screenshot after dragging the right-edge thumb: the transcript follows the drag and settles at a different position than before. No stuck thumb, no transcript jump-back while dragging. |
| 2 | Send a long message; the composer toggles open/close without losing focus. | Send a multi-line message; then tap the keyboard (hide) button, then the floating composer button. Evidence: two screenshots (composer hidden, composer restored) plus the final sent message present in the transcript. Focus stays in the text field — typing continues without re-tapping. |
| 3 | Toggle airplane mode; "The connection is offline…" appears. | Turn on airplane mode, then try to send (or open a session whose load fails). Evidence: screenshot showing the composer with the send arrow visible but inert and the copy line "The connection is offline; your message will send when the host is back." beneath it. Tapping send does nothing (no new bubble, no error dialog). Turn airplane mode off; the next successful send clears the line. |
| 4 | App background then foreground: no scroll-position jump. | Scroll up to a message mid-transcript, background the app (Home), return within a few seconds. Evidence: screenshot showing the same message still in view — the transcript did not snap to the bottom or top. |
| 5 | With JKPHermex installed: deep-link round trip from the super-app lands in the JKP chat surface within 2s. | Trigger the `faroukfusion://open-jkp` deep link from the super-app (the super-app's own link — no `adb` needed). Evidence: a screen recording or timestamped screenshots showing the chat surface visible within 2 seconds of the tap, plus a logcat line if the device is plugged in. |

Pass criteria: 5/5 checks pass with the named evidence; any failure opens a
known-issue row before further release work.
