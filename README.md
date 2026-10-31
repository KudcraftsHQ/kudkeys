# Kud Keys

A personal Android keyboard: SwiftKey's layout, voice as a first-class way to type, and
clear rules for when it learns (never in incognito, search bars, URLs or passwords).

| Part | Where | Status |
|---|---|---|
| Server: voice WebSocket (Groq Whisper proxy), accounts API, web app | [`server/`](server/README.md), [`app/`](app/) | voice deployed at `keys.kudcrafts.com`; accounts + web app on `v0.2` |
| Keyboard app (HeliBoard fork) | `android/` | next (v0.1) |

Releases ship as signed APKs on GitHub Releases, installed and updated with Obtainium,
the same way as Kud Widgets.
