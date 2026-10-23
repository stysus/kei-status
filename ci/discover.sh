#!/usr/bin/env bash
#
# Writes the shard plan a sweep runs.
#
# There is no hand-maintained module list. Gradle registers every `src/<lang>/<name>` directory as
# a module (`loadAllIndividualExtensions()` in `extensions-source/settings.gradle.kts`), and every
# real extension carries a `build.gradle.kts`. That rule lives in the checker (`ModuleList`), which
# this script calls: the shard list CI runs and the list a local `--shard` produces therefore come
# from the same code, and a change to the rule cannot leave one of them behind.
#
# What stays here is the part with no equivalent in the checker: turning the plan into the GitHub
# Actions matrix, because that shape is GitHub's, not the harness's.
#
# Usage: discover.sh <extensions-source-dir> <out-dir> <shard-count> [list-file]
#
# With a [list-file], that file is used instead of the tree, which is how a manual run is limited
# to a few modules without keeping a second copy of the discovery logic.
#
# Requires the checker to be built: ./gradlew :cli:installDist
#
# Writes:
#   <out-dir>/all.txt         every module, one per line, sorted
#   <out-dir>/shard-<n>.txt   shard n (may be empty when there are fewer modules than shards)
#   <out-dir>/matrix.json     GitHub Actions matrix: {"include":[{"shard":"1","file":"..."}]}
#   <out-dir>/complete        empty marker, written only when the list came from the tree. With a
#                             [list-file] the list is a subset, and `publish.sh` would delete every
#                             module it does not name if it pruned against that
#
# When GITHUB_OUTPUT is set, also exports `matrix`, `total` and `shards` to the job.

set -euo pipefail

usage="usage: discover.sh <extensions-source-dir> <out-dir> <shard-count> [list-file]"
source_root="${1:?$usage}"
out="${2:?$usage}"
count="${3:?$usage}"
list_file="${4:-}"

if ! [[ "$count" =~ ^[0-9]+$ ]] || [ "$count" -lt 1 ]; then
  echo "shard count must be a positive integer, got: $count" >&2
  exit 2
fi

cli="./cli/build/install/cli/bin/cli"
if [ ! -x "$cli" ]; then
  echo "the checker is not built at $cli; run ./gradlew :cli:installDist first" >&2
  exit 1
fi

rm -rf "$out"

# `--store data` is what the shard split balances on: the checker reads how many scenarios each
# module ran last time and spreads the heavy ones out, so the modules that declare a hundred sources
# do not all land in the same shard. The path is passed rather than defaulted so this dependency on
# the committed store is visible here instead of hidden in the checker.
if [ -n "$list_file" ]; then
  "$cli" --plan "$out" --shards "$count" --store data --module-list "$list_file"
else
  "$cli" --plan "$out" --shards "$count" --store data --discover --source-root "$source_root"

  # Marks the list as every module in the tree. `publish.sh` prunes the store only when this file
  # is present: a run limited with the `modules` input writes a list of just those modules, and
  # pruning against it would delete every other module's stored state.
  : >"$out/complete"
fi

total=$(wc -l <"$out/all.txt")

matrix='{"include":['
first=1
shards=0
for ((n = 1; n <= count; n++)); do
  file="$out/shard-$n.txt"
  [ -s "$file" ] || continue
  [ "$first" -eq 1 ] || matrix+=','
  matrix+="{\"shard\":\"$n\",\"file\":\"$file\"}"
  first=0
  shards=$((shards + 1))
done
matrix+=']}'

printf '%s\n' "$matrix" >"$out/matrix.json"

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  {
    echo "matrix=$matrix"
    echo "total=$total"
    echo "shards=$shards"
  } >>"$GITHUB_OUTPUT"
fi
