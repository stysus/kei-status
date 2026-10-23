#!/usr/bin/env bash
#
# Renders the static status page from the stored reports.
#
# `--only` is the list of modules that exist right now, so an extension deleted upstream stops
# being presented as checked. When that list is the whole tree, the store entries it does not name
# are deleted as well: nothing sweeps a removed module, so nothing would ever rewrite its entry and
# the store would keep one dead file per upstream removal forever.
#
# `discover.sh` leaves a `complete` marker next to the list when it read the whole tree. A run
# limited with the `modules` input has no marker, because its list is a subset and pruning against
# it would delete every module it did not name.
#
# Usage: publish.sh <all-modules-file> [out-dir] [store-dir]

set -euo pipefail

usage="usage: publish.sh <all-modules-file> [out-dir] [store-dir]"
modules="${1:?$usage}"
out="${2:-site}"
store="${3:-data}"

prune=()
if [ -f "$(dirname "$modules")/complete" ]; then
  prune=(--prune)
fi

./cli/build/install/cli/bin/cli \
  --site "$store" \
  --out "$out" \
  --only "$modules" \
  "${prune[@]}"
