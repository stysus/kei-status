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

# Union the per-shard costs files into a single costs file in the store.  Tonight's measurement
# wins per module; a module that was not checked tonight (a manual run, say) keeps the timing
# from the last night that did check it.
python3 - "$store/costs" "$incoming" <<'PY'
import glob, json, os, sys

store_costs, incoming = sys.argv[1:3]
merged = {}
if os.path.isfile(store_costs):
    try:
        merged = json.load(open(store_costs))
    except ValueError as e:
        print(f"::warning::existing costs file unreadable, starting fresh: {e}", file=sys.stderr)
for path in sorted(glob.glob(os.path.join(incoming, "**", "costs-*"), recursive=True)):
    try:
        merged.update(json.load(open(path)))
    except ValueError as e:
        print(f"::warning::ignoring unreadable {path}: {e}", file=sys.stderr)
if merged:
    with open(store_costs, "w") as f:
        json.dump(merged, f, indent=2, sort_keys=True)
        f.write("\n")
PY

applied=0
while IFS= read -r file; do
  cp -f "$file" "$store/"
  applied=$((applied + 1))
done < <(find "$incoming" -name '*.json' -type f)

echo "applied $applied store entr(ies)"
