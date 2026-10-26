#!/usr/bin/env bash
#
# Gathers the store entries one shard rewrote, so the publish job can merge them without every
# shard uploading the entire store.
#
# The store only rewrites a file when a module's state actually changes, so this is usually a
# handful of files. A marker file records that the shard finished, which is how the publish job
# tells "nothing changed here" apart from "this shard never ran".
#
# The store is a checkout of the `data` branch, so its own git index is what says which entries the
# run rewrote; the paths it reports are relative to the store root.
#
# Usage: collect.sh <shard-id> <store-dir> <out-dir>

set -euo pipefail

usage="usage: collect.sh <shard-id> <store-dir> <out-dir>"
shard="${1:?$usage}"
store="${2:-data}"
out="${3:?$usage}"

if ! git -C "$store" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "::error::$store is not a git checkout, so rewritten entries cannot be detected" >&2
  exit 1
fi

rm -rf "$out"
mkdir -p "$out"

count=0
while IFS= read -r entry; do
  [ -n "$entry" ] || continue
  code="${entry:0:2}"
  file="${entry:3}"
  # A shard only writes entries; a removal comes from `publish.sh --prune` in the publish job, which
  # has the shard list to prove the module is gone. Copying a path that no longer exists would take
  # the shard down over a file nobody reads.
  case "$code" in
    *D*) continue ;;
  esac
  # The costs file is a per-shard view of module wall-clock times; merging partial views from
  # different shards would give the planning step an incomplete picture.  The file that matters
  # is the one in the local store checkout, which saw every module this JVM checked.
  case "$file" in costs) continue ;; esac
  cp "$store/$file" "$out/"
  count=$((count + 1))
done < <(git -C "$store" status --porcelain -z --untracked-files=all | tr '\0' '\n')

touch "$out/shard-$shard.done"
echo "shard $shard rewrote $count store entr(ies)"
