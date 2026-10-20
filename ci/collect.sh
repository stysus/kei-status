#!/usr/bin/env bash
#
# Gathers the store entries one shard rewrote, so the publish job can merge them without every
# shard uploading the entire store.
#
# The store only rewrites a file when a module's state actually changes, so this is usually a
# handful of files. A marker file records that the shard finished, which is how the publish job
# tells "nothing changed here" apart from "this shard never ran".
#
# Usage: collect.sh <shard-id> <store-dir> <out-dir>

set -euo pipefail

usage="usage: collect.sh <shard-id> <store-dir> <out-dir>"
shard="${1:?$usage}"
store="${2:-data}"
out="${3:?$usage}"

rm -rf "$out"
mkdir -p "$out"

count=0
while IFS= read -r file; do
  [ -n "$file" ] || continue
  cp "$file" "$out/"
  count=$((count + 1))
done < <(git status --porcelain -z --untracked-files=all -- "$store" | tr '\0' '\n' | sed -n 's/^...//p')

touch "$out/shard-$shard.done"
echo "shard $shard rewrote $count store entr(ies)"
