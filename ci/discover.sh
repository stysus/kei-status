#!/usr/bin/env bash
#
# Discovers every extension module in an extensions-source checkout and splits it into shards.
#
# There is no hand-maintained module list. Gradle registers every `src/<lang>/<name>` directory as
# a module (`loadAllIndividualExtensions()` in `extensions-source/settings.gradle.kts`), and every
# real extension carries a `build.gradle.kts`. That file is what tells an extension from a stray
# directory, so an extension added upstream is picked up by the next run without anyone editing a
# file here.
#
# Usage: discover.sh <extensions-source-dir> <out-dir> <shard-count> [list-file]
#
# With a [list-file], that file is used instead of the tree, which is how a manual run is limited
# to a few modules without keeping a second copy of the discovery logic.
#
# Writes:
#   <out-dir>/all.txt         every module, one per line, sorted
#   <out-dir>/shard-<n>.txt   shard n (may be empty when there are fewer modules than shards)
#   <out-dir>/matrix.json     GitHub Actions matrix: {"include":[{"shard":"1","file":"..."}]}
#
# When GITHUB_OUTPUT is set, also exports `matrix` and `total` to the job.

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

src="$source_root/src"

rm -rf "$out"
mkdir -p "$out"

if [ -n "$list_file" ]; then
  sed '/^[[:space:]]*#/d; /^[[:space:]]*$/d' "$list_file" | sort >"$out/all.txt"
else
  if [ ! -d "$src" ]; then
    echo "no source tree at $src; is the extensions-source checkout present?" >&2
    exit 1
  fi

  find "$src" -mindepth 2 -maxdepth 2 -type d ! -name '.*' -print0 |
    while IFS= read -r -d '' dir; do
      # A directory is a Gradle module only when it has a build script; anything else in the tree
      # (assets, scratch directories) is not an extension.
      [ -f "$dir/build.gradle.kts" ] || continue
      rel="${dir#"$src"/}"
      printf ':src:%s\n' "${rel//\//:}"
    done |
    sort >"$out/all.txt"
fi

total=$(wc -l <"$out/all.txt")
if [ "$total" -eq 0 ]; then
  echo "no modules discovered under $src" >&2
  exit 1
fi

# Contiguous, evenly sized shards. Assignment is stable for a stable list, which keeps each
# shard's Gradle build cache key valid from run to run.
awk -v n="$count" -v out="$out" '
  { lines[NR] = $0 }
  END {
    size = int((NR + n - 1) / n)
    for (s = 1; s <= n; s++) {
      file = out "/shard-" s ".txt"
      printf "" > file
      for (i = (s - 1) * size + 1; i <= s * size && i <= NR; i++) print lines[i] > file
      close(file)
    }
  }
' "$out/all.txt"

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
echo "discovered $total module(s) in $shards shard(s)"

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  {
    echo "matrix=$matrix"
    echo "total=$total"
    echo "shards=$shards"
  } >>"$GITHUB_OUTPUT"
fi
