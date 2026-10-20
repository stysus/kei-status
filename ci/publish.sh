#!/usr/bin/env bash
#
# Renders the static status page from the stored reports.
#
# `--only` is the list of modules that exist right now, so an extension deleted upstream stops
# being presented as checked even though its old report is still in the store.
#
# Usage: publish.sh <all-modules-file> [out-dir] [store-dir]

set -euo pipefail

usage="usage: publish.sh <all-modules-file> [out-dir] [store-dir]"
modules="${1:?$usage}"
out="${2:-site}"
store="${3:-data}"

./cli/build/install/cli/bin/cli \
  --site "$store" \
  --out "$out" \
  --only "$modules" \
  --title "Keiyoushi extension status"
