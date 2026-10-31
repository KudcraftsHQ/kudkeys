#!/usr/bin/env bash
# Fails if R8 stripped a constructor that Android or a library calls by reflection.
# v0.4.0–v0.4.3 crashed on every start because WorkDatabase_Impl lost its constructor.
# Usage: check-reflection-constructors.sh <apk>
set -euo pipefail
apk=$1
dexdump=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/dexdump | sort -V | tail -1)
work=$(mktemp -d); trap 'rm -rf "$work"' EXIT
unzip -q -o "$apk" 'classes*.dex' -d "$work"
dump="$work/dump.txt"
for f in "$work"/classes*.dex; do "$dexdump" "$f"; done > "$dump"
fail=0
for cls in \
  'Landroidx/work/impl/WorkDatabase_Impl;' \
  'Landroidx/work/OverwritingInputMerger;' \
  'Lhelium314/keyboard/kudkeys/sync/KudSyncWorker;' \
  'Lhelium314/keyboard/kudkeys/sync/KudBackupWorker;' \
  'Lhelium314/keyboard/latin/App;'; do
  if ! awk -v c="$cls" '
      index($0, "Class descriptor") && index($0, "'\''" c "'\''") {p=1; next}
      p && index($0, "Class descriptor") {p=0}
      p && /name *: .<init>./ {found=1}
      END {exit found ? 0 : 1}' "$dump"; then
    echo "MISSING constructor: $cls" >&2; fail=1
  fi
done
[ $fail -eq 0 ] && echo "reflection constructors OK"
exit $fail
