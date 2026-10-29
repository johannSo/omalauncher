#!/usr/bin/env bash
# Renders store/screenshots/fork.html (captions + raw/fork-* captures) into the fork's README screenshots,
# art/fork/01.png.., 1080x1920 each, framed like the Play listing's. Uses headless Firefox, with a profile of its
# own so a running Firefox doesn't get the call.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FIREFOX="${FIREFOX:-firefox}"
OUT="$ROOT/art/fork"
COUNT=4

PROFILE=$(mktemp -d)
trap 'rm -rf "$PROFILE"' EXIT
mkdir -p "$OUT"
rm -f "$OUT"/*.png
for i in $(seq 1 $COUNT); do
  file=$(printf '%s/%02d.png' "$OUT" "$i")
  "$FIREFOX" --headless --no-remote --profile "$PROFILE" --window-size=1080,1920 \
    --screenshot "$file" "file://$ROOT/store/screenshots/fork.html?s=$i" >/dev/null 2>&1
  echo "$file"
done
