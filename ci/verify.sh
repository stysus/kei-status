#!/usr/bin/env bash
#
# Guards the publish step. A page is only worth publishing when the run behind it actually ran.
#
# Three things are checked, in order of how badly they would mislead a reader:
#
#   1. every shard reported in, so "last checked" is true for the whole page and not just for the
#      shards that happened to finish;
#   2. at least one report loaded a source, because "everything failed to load" means the harness
#      or the extension build is broken, not that 1,400 sites went down at once;
#   3. the store is not empty.
#
# Usage: verify.sh <store-dir> <incoming-dir> <matrix.json>

set -euo pipefail

usage="usage: verify.sh <store-dir> <incoming-dir> <matrix.json>"
store="${1:?$usage}"
incoming="${2:?$usage}"
matrix="${3:?$usage}"

python3 - "$store" "$incoming" "$matrix" <<'PY'
import glob
import json
import os
import sys

store, incoming, matrix_path = sys.argv[1:4]

expected = {entry["shard"] for entry in json.load(open(matrix_path))["include"]}
reported = {
    os.path.basename(path)[len("shard-"):-len(".done")]
    for path in glob.glob(os.path.join(incoming, "**", "shard-*.done"), recursive=True)
}
missing = sorted(expected - reported, key=int)
if missing:
    sys.exit(
        f"{len(missing)}/{len(expected)} shard(s) never reported in: {', '.join(missing)}\n"
        "The page is not published, because a partial sweep would present stale modules as checked."
    )

reports = {}
for path in glob.glob(os.path.join(store, "*.json")):
    try:
        report = json.load(open(path))
    except Exception:
        continue
    reports[report.get("module", path)] = report

loaded = [module for module, report in reports.items() if report.get("sources")]
print(f"{len(loaded)}/{len(reports)} stored report(s) loaded at least one source")

if not reports:
    sys.exit("the store is empty; nothing to publish")
if not loaded:
    sys.exit("every module failed to load; the harness or the extension build is broken")
PY
