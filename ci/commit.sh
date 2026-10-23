#!/usr/bin/env bash
#
# Commits the state the sweep recorded.
#
# The store is the memory of the project: it is committed so the next run can diff against it and
# so the history of what broke when lives in git. Nothing is committed when the store did not move,
# which is the common case on a quiet night. A module deleted upstream moves it too: `publish.sh`
# prunes the entry, and this records the removal.
#
# A push can land while the sweep is still running, so the push is retried on a rebase.
#
# Usage: commit.sh [store-dir] [message-prefix]

set -euo pipefail

store="${1:-data}"
prefix="${2:-status}"

if [ -z "$(git status --porcelain -- "$store")" ]; then
  echo "no status changed; nothing to commit"
  exit 0
fi

changed=0
removed=0
while IFS= read -r -d '' entry; do
  code="${entry:0:2}"
  # A rename or copy carries its original path as the next record, which is not a second change.
  case "$code" in
    R* | C*) IFS= read -r -d '' _original || true ;;
  esac
  case "$code" in
    *D*) removed=$((removed + 1)) ;;
    *) changed=$((changed + 1)) ;;
  esac
done < <(git status --porcelain -z -- "$store")

# Name only what moved: a quiet run should not read "0 removed", and a night that only pruned should
# not read "0 changed". At least one of the two is non-zero, because an empty status returned above.
if [ "$changed" -gt 0 ] && [ "$removed" -gt 0 ]; then
  summary="$changed changed, $removed removed"
elif [ "$changed" -gt 0 ]; then
  summary="$changed changed"
else
  summary="$removed removed"
fi

git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add -- "$store"
git commit -m "$prefix: record $summary [skip ci]"

for attempt in 1 2 3; do
  if git push; then
    echo "pushed $summary"
    exit 0
  fi
  echo "push failed (attempt $attempt); rebasing on the new head"
  git pull --rebase origin "$GITHUB_REF_NAME"
done

echo "::error::could not push the recorded state after 3 attempts"
exit 1
