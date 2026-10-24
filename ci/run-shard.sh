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
#
# The challenge solver needs a visible browser. A headless one is detectable and a Cloudflare
# challenge it refuses never becomes solvable, so a display is supplied when one can be created.
# Without `xvfb-run` the browser falls back to headless and challenges stay unsolved.
runner=()
if command -v xvfb-run >/dev/null 2>&1; then
  runner=(xvfb-run -a)
else
  echo "::warning::xvfb-run is not installed; challenges will be attempted headlessly and may not clear"
fi

# The checker's verdict is written to a file by the shell that runs it, not taken from the exit
# status of this pipeline: Debian's `xvfb-run` overwrites the child's exit code with its own when
# its cleanup fails (exit 5, "problem while cleaning up temporary directory"), and that once marked
# a shard whose 76 modules had all just finished checking, so the publish job refused the whole
# sweep. The wrapper's parting failure must not mask what the checker found.
#
# The checker's output is teed to a log for the same reason: its exit code cannot reject a run
# that checked nothing, because 1 is already the sweep's normal finding. The summary line is the
# only place that says how many modules survived.
status_file=$(mktemp)
log_file=$(mktemp)

set +e
"${runner[@]}" bash -c '
  log="$1"; shift
  "$@" 2>&1 | tee "$log"
  st=${PIPESTATUS[0]}
  printf "%s\n" "$st" >"$0"
  exit "$st"
' "$status_file" "$log_file" ./cli/build/install/cli/bin/cli \
  --module-list "$shard_file" \
  --source-root "$source_root" \
  --store "$store"
set -e

if [ ! -s "$status_file" ]; then
  echo "::error::the checker never ran; the display wrapper failed before starting it"
  exit 1
fi
code="$(cat "$status_file")"
rm -f "$status_file"

# Read once, then drop: every exit below uses the summary, and the temp log should not outlive
# this script on a local machine where the workspace is not cleaned up for us.
summary=$(grep '^0 module(s) checked' "$log_file" || true)
rm -f "$log_file"

case "$code" in
  0 | 1) ;;
  *)
    echo "::error::the checker exited $code"
    exit "$code"
    ;;
esac

# Exit 1 tolerates "at least one source failed", so an empty run reaches here as a success. The
# summary line is what distinguishes it: 0 checked means every entry was dropped before producing
# a verdict (a malformed list, a checker bug), and recording a marker for that would let a broken
# harness look like a quiet night.
if [ -n "$summary" ]; then
  echo "::error::the checker ran but checked 0 module(s); every entry was dropped before producing a verdict"
  exit 1
fi
