#!/usr/bin/env bash
#
# Commits the state the sweep recorded.
#
# The store is the memory of the project: it is committed so the next run can diff against it and
# so the history of what broke when lives in git. Nothing is committed when no module changed,
# which is the common case on a quiet night.
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

changed=$(git status --porcelain -z -- "$store" | tr '\0' '\n' | grep -c . || true)

git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add -- "$store"
git commit -m "$prefix: record $changed changed module(s) [skip ci]"

for attempt in 1 2 3; do
  if git push; then
    echo "pushed $changed changed module(s)"
    exit 0
  fi
  echo "push failed (attempt $attempt); rebasing on the new head"
  git pull --rebase origin "$GITHUB_REF_NAME"
done

echo "::error::could not push the recorded state after 3 attempts"
exit 1
