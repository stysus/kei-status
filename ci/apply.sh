#!/usr/bin/env bash
#
# Merges the store entries the shards uploaded into the committed store.
#
# A shard uploads only the files it rewrote, so this is a flat copy: every store file is named
# after its module and belongs at the top level of the store. A module whose state did not move has
# no file here and keeps the entry already in git.
#
# Usage: apply.sh <incoming-dir> [store-dir]

set -euo pipefail

usage="usage: apply.sh <incoming-dir> [store-dir]"
incoming="${1:?$usage}"
store="${2:-data}"

if [ ! -d "$incoming" ]; then
  echo "no downloaded state at $incoming" >&2
  exit 1
fi

mkdir -p "$store"

applied=0
while IFS= read -r file; do
  cp -f "$file" "$store/"
  applied=$((applied + 1))
done < <(find "$incoming" -name '*.json' -type f)

echo "applied $applied store entr(ies)"
