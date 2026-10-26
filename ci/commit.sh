#!/usr/bin/env bash
#
# Commits the state the sweep recorded, on the branch that holds it.
#
# The store is not part of the code history: it lives on its own branch (`data`), and `data/` is a
# checkout of that branch that `.gitignore` keeps out of this tree. A nightly state commit therefore
# never appears in `git log` for the harness itself, which is the whole point of the split.
#
# Nothing is committed when the store did not move, which is the common case on a quiet night. A
# module deleted upstream moves it too: `publish.sh` prunes the entry, and this records the removal.
#
# A push can land while the sweep is still running, so the push is retried on a rebase.
#
# Usage: commit.sh [store-dir] [branch] [message-prefix]

set -euo pipefail

store="${1:-data}"
branch="${2:-data}"
prefix="${3:-status}"

if ! git -C "$store" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "::error::$store is not a git checkout, so there is no branch to commit the state to" >&2
  echo "Set the store up as a checkout of the $branch branch first:" >&2
  echo "  git worktree add $store $branch" >&2
  exit 1
fi

if [ -z "$(git -C "$store" status --porcelain)" ]; then
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
done < <(git -C "$store" status --porcelain -z)

# Name only what moved: a quiet run should not read "0 removed", and a night that only pruned should
# not read "0 changed". At least one of the two is non-zero, because an empty status returned above.
if [ "$changed" -gt 0 ] && [ "$removed" -gt 0 ]; then
  summary="$changed changed, $removed removed"
elif [ "$changed" -gt 0 ]; then
  summary="$changed changed"
else
  summary="$removed removed"
fi

git -C "$store" config user.name "github-actions[bot]"
git -C "$store" config user.email "41898282+github-actions[bot]@users.noreply.github.com"

# `actions/checkout` can leave a detached HEAD, and a detached commit has nowhere to push from.
git -C "$store" checkout -q -B "$branch"

# A conflict during rebase leaves a half-applied rebase state behind: the rebase-merge directory,
# the unmerged conflict markers in the file, and a HEAD that cannot push. A previous run's leftover
# state would make the next `git add` commit the conflict markers as JSON, silently corrupting the
# store. Aborting before we start is cheaper than diagnosing that.
if [ -d "$store/.git/rebase-merge" ] || [ -d "$store/.git/rebase-apply" ]; then
  echo "::warning::aborting a rebase left over from a previous run"
  git -C "$store" rebase --abort
fi

git -C "$store" add --all -- .
git -C "$store" commit -m "$prefix: record $summary [skip ci]"

push_cleanup() { git -C "$store" rebase --abort 2>/dev/null || true; }
trap push_cleanup EXIT

for attempt in 1 2 3; do
  if git -C "$store" push origin "$branch"; then
    trap - EXIT
    echo "pushed $summary to $branch"
    exit 0
  fi
  echo "push failed (attempt $attempt); rebasing on the new head"
  git -C "$store" pull --rebase origin "$branch" || {
    echo "::error::rebase conflict on attempt $attempt; the same module was rewritten by a concurrent sweep"
    exit 1
  }
done

if git -C "$store" push origin "$branch"; then
  trap - EXIT
  echo "pushed $summary to $branch (final attempt after rebase)"
  exit 0
fi

trap - EXIT
echo "::error::could not push the recorded state after all attempts"
exit 1
