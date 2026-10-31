# Kud Keys — Android keyboard

Kud Keys is Hammas's personal Android keyboard (English + Indonesian typed together, voice input via the
Kud Keys voice server in `../server`). It is a fork of **[HeliBoard](https://github.com/HeliBorg/HeliBoard)**
by Helium314 and contributors, which is itself based on the AOSP LatinIME keyboard.

## Licence and credit

Kud Keys is free software under the **GNU General Public License v3.0**, like HeliBoard. See [LICENSE](LICENSE)
(GPL-3.0), [LICENSE-Apache-2.0](LICENSE-Apache-2.0) for the AOSP-derived parts and
[LICENSE-CC-BY-SA-4.0](LICENSE-CC-BY-SA-4.0) for the HeliBoard artwork, exactly as upstream ships them.
All credit for the keyboard engine, layouts, dictionaries tooling and settings app goes to HeliBoard and the
AOSP keyboard authors; HeliBoard's own README is kept as [HELIBOARD-README.md](HELIBOARD-README.md).
The About screen in the app shows the same credit and licence.

## What differs from HeliBoard

- applicationId `com.kudcrafts.kudkeys`, name "Kud Keys", own icon. The Java/Kotlin package stays
  `helium314.keyboard` so upstream merges stay simple.
- No gesture/swipe typing and no clipboard history.
- Defaults: English (US) with Indonesian as a secondary language, dark theme, no key borders, no number row.
- Bundled dictionaries: `en-US` and `id` only (others can still be added from HeliBoard's dictionary repo).

See [NOTES.md](NOTES.md) for where things live and how to build, and [UPSTREAM.md](UPSTREAM.md) for the
upstream version and how to merge newer HeliBoard releases.
