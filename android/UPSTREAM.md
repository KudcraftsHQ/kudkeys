# Upstream: HeliBoard

`android/` is a plain-source copy of HeliBoard (no submodule, no shared git history).

| | |
|---|---|
| Upstream repo | https://github.com/Helium314/HeliBoard (now `HeliBorg/HeliBoard`) |
| Imported tag | `v4.2` (latest stable on 2026-10-31) |
| Imported commit | `0c60dffb03a4e2e7a37cb82210f5299f93c03979` |
| Import commit here | the first commit touching `android/` ("Import HeliBoard v4.2 source into android/") |
| Not copied | `.git`, `.github/` (HeliBoard's CI), `fastlane/`, `.idea/` |
| Upstream version code | 4201, kept as `UPSTREAM_BASE_VERSION_CODE` in `latin/AppUpgrade.kt` |

The Java/Kotlin package (`helium314.keyboard…`) and the namespace are unchanged on purpose: it keeps the diff
against upstream small, so merges stay mechanical. Only the applicationId changed.

## Pulling a newer HeliBoard release

Apply upstream's own diff between the two tags onto `android/`, with a three-way merge:

```bash
cd "$TMPDIR" && git clone https://github.com/HeliBorg/HeliBoard.git heliboard && cd heliboard
OLD=v4.2 NEW=v4.3          # NEW = the release you want
git diff "$OLD" "$NEW" -- . ':!.github' ':!fastlane' ':!.idea' > "$TMPDIR/heliboard-$NEW.patch"
cd ~/Projects/kudkeys
git apply --3way --directory=android "$TMPDIR/heliboard-$NEW.patch"
```

`--3way` needs the old blobs, which this repo has from the import commit. Resolve conflicts, then update the
tag/commit table above and `UPSTREAM_BASE_VERSION_CODE`.

Things to watch when merging:

- **Upgrade migrations** (`latin/AppUpgrade.kt`): HeliBoard gates them on its own version codes
  (`if (oldVersion <= 4205) …`). Kud Keys version codes are unrelated (`major*10000 + minor*100 + patch`), so
  `onUpgrade` pins `oldVersion` to `UPSTREAM_BASE_VERSION_CODE`. A new upstream migration will therefore
  never run as-is; if Kud Keys needs it, gate it on a Kud Keys version code instead.
- **Removed features**: gesture typing (settings, library loading, gesture-data gathering) and clipboard
  history (view, DB, provider, toolbar keys) were deleted. Upstream changes to those files will conflict on
  deleted paths; drop them unless the feature comes back.
- **Bundled dictionaries**: we ship only `main_en-US.dict` and `main_id.dict`. Upstream updates to other
  `assets/dicts/main_*.dict` files can be dropped.
- **Build file**: `app/build.gradle.kts` is ours (applicationId, signing, two build types). Port dependency
  and SDK bumps by hand.

## Bundled Indonesian dictionary

`app/src/main/assets/dicts/main_id.dict` is `dictionaries/main_id__experimental.dict` from HeliBoard's
dictionary repository, https://codeberg.org/Helium314/aosp-dictionaries (commit
`ff0cd52be8d9530200fefe8b296c53d4f662091e`, dictionary dated 2024-01-17, 229 483 words, with next-word data).
Its SHA-256 is listed in `assets/known_dict_hashes.txt`.

- Source: built with the repo's `wordlist.py` / `wordlist_combined.py` from the Leipzig Corpora Collection
  word lists (https://wortschatz.uni-leipzig.de/en/download/).
- Licence: the source word lists are under **CC BY 4.0** (https://creativecommons.org/licenses/by/4.0/),
  per the repo's `wordlists/main_id__experimental.source`. Attribution: Leipzig Corpora Collection, and
  HeliBoard's aosp-dictionaries repository for the compiled dictionary.
- It is "experimental" upstream (generated from corpora, not curated). No non-experimental Indonesian main
  dictionary exists there.

`main_en-US.dict` is the one HeliBoard v4.2 ships.
