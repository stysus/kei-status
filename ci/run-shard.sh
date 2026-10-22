#!/usr/bin/env bash
#
# Builds and runs one shard of the sweep.
#
# The whole shard is built in a single Gradle invocation so the shared modules (`:core`, `:lib:*`,
# `:lib-multisrc:*`) compile once instead of once per extension, and so compilation runs in
# parallel. The Gradle build cache is what makes a re-run cheap: an extension whose sources did not
# change upstream is restored from cache instead of recompiled, so a sweep after a quiet night
# spends its time checking sites rather than building them.
#
# Usage: run-shard.sh <shard-file> [store-dir] [source-root]

set -euo pipefail

usage="usage: run-shard.sh <shard-file> [store-dir] [source-root]"
shard_file="${1:?$usage}"
store="${2:-data}"
source_root="${3:-extensions-source}"

if [ ! -f "$shard_file" ]; then
  echo "no such shard file: $shard_file" >&2
  exit 2
fi

if [ ! -s "$shard_file" ]; then
  echo "shard $shard_file is empty; nothing to run"
  exit 0
fi

# Absolute, because Gradle resolves a relative `-PharnessModuleList` against the extension build
# directory rather than the caller's.
shard_file="$(realpath "$shard_file")"

echo "checking $(wc -l <"$shard_file") module(s) from $shard_file"
mkdir -p "$store"

# `--continue` keeps one extension that does not compile from hiding the rest of the shard. The
# harness records a module without a layout file as a failed load, so a broken extension still
# appears on the page with its reason instead of disappearing.
if ! "$source_root/gradlew" \
  -p "$source_root" \
  -I "$PWD/tools/harness.init.gradle.kts" \
  -PharnessModuleList="$shard_file" \
  dumpAllHarnessClasspaths \
  --continue \
  --console=plain; then
  echo "::warning::some modules in this shard did not compile; they will be reported as failed loads"
fi

# `--timeout` is not passed: the checker's own default is the value a sweep wants, and repeating it
# here meant the same number lived in two places.
#
# Exit 1 means "at least one source failed", which is a finding the page records. In a sweep of
# 1,400 modules that is the normal case, so it is not a warning. Anything else is the checker
# itself breaking, and that must fail the job.
set +e
./cli/build/install/cli/bin/cli \
  --module-list "$shard_file" \
  --source-root "$source_root" \
  --store "$store"
code=$?
set -e

case "$code" in
  0 | 1) ;;
  *)
    echo "::error::the checker exited $code"
    exit "$code"
    ;;
esac
