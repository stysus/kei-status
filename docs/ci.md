# Continuous integration

## The two workflows

Two workflows, split by what they can prove:

| File | Runs on | Does |
| --- | --- | --- |
| `.github/workflows/ci.yml` | every pull request and push | builds the harness and runs the unit tests; lints the workflows and the CI scripts. No network. |
| `.github/workflows/status.yml` | nightly, on demand | checks every extension against its live site and publishes the result to GitHub Pages. |

A sweep hits every live site and takes hours of runner time, so it is deliberately not wired to
push: a commit that touches the harness is guarded by `ci.yml`, and the published page is refreshed
by the nightly run or by asking for it. To trigger one by hand:

```
gh workflow run status.yml
```

Its `modules` input narrows a run to a few extensions, which is the cheap way to try a change to the
sweep itself without checking all 1,400+.

## There is no module list

`ci/discover.sh` reads the tree. `extensions-source` registers every `src/<lang>/<name>` directory
as a Gradle module (`loadAllIndividualExtensions()` in `extensions-source/settings.gradle.kts`), and
every real extension carries a `build.gradle.kts` — that file is what tells an extension from a
stray directory. An extension added upstream is checked by the next run without anyone editing a
file here; a hand-written list would go stale the day after it was written.

That rule, and the split into shards, live in the checker (`ModuleList`): `ci/discover.sh` calls
`--plan`, which writes `all.txt` and the shard files. Only the GitHub Actions matrix is built by the
script, because that shape belongs to GitHub rather than to the harness. A local `--shard` and the
CI matrix therefore cannot disagree about which modules a shard holds, as long as both are pointed at
the same store: the split reads its weights from there.

`--module-list` still exists for a one-off run, and the `modules` input on the `workflow_dispatch`
trigger feeds one in without committing it.

## Sharding

1,400+ modules do not fit in one job, so the discovered list is split into `20` shards (`shards`
input to change it) and each shard runs in its own job. Shards are independent: `fail-fast: false`,
so one bad shard does not cancel the rest.

The split is by **cost, not by module count**: the checker packs the heaviest first onto the
emptiest shard. The measurements behind that decision are in
[design.md](design.md#sharding-balances-cost-not-module-count).

The weights have two sources, in order: the `costs` file in the store — wall-clock per module from
the last sweep — and, for a module it does not name (first run, a fresh checkout), the store's
scenario durations. The file never travels with the reports: each shard's `collect.sh` copies its
own view out as `costs-<shard>` so the artifact merge cannot let one shard clobber another,
`apply.sh` unions them into one `costs` in the publish job (tonight's timing wins per module; a
module a manual run did not check keeps its last timing), and `commit.sh` counts it separately from
real state changes. Shards are disjoint by construction and `verify.sh` refuses to publish unless
every shard reported, so the nightly union is complete for the modules the night measured.

A shard compiles its own modules in one Gradle invocation, so the shared modules (`:core`, `:lib:*`)
compile once instead of once per extension and compilation runs in parallel. What a shard does *not*
build is the harness: that is shared by every shard and is built once, in `prepare`.

Every shard checks out the revision `prepare` discovered the tree from, rather than asking for `main`
independently. Twenty checkouts of a moving branch can land on different commits, and then a module
the shard list names may not exist in the tree a shard actually compiled — which reads on the page as
a failed load. Pinning also keeps the build-cache key in agreement with the tree.

## Build caching

Each shard keeps its own Gradle build cache (`~/.gradle/caches/build-cache-1`) under a key scoped to
that shard, plus one shared cache for downloaded dependencies; the harness is built once in `prepare`
and handed to the shards as an artifact. The reasoning and the numbers are in
[design.md](design.md#the-build-cache-is-per-shard).

## What gets published

Each shard passes `--store data`, so results persist in the repository instead of living only in the
workspace of that one run. The store only rewrites a report when a module's state actually moves.
The one file that moves every night is `costs` — measured wall-clock per module is never identical —
so a quiet night now produces one commit that says `measured costs updated` instead of none.
`git log data` therefore reads `status: record N changed` on an active night and
`status: record measured costs updated` on a quiet one, and `git log -p data -- costs` is the
wall-clock timeline. A module that disappears upstream is the other case: `--prune` deletes its
entry, and that night commits a removal instead of a rewrite.

Shards upload only the files they rewrote, plus a marker recording that the shard finished. The
publish job merges those, refuses to publish unless **every** shard reported in, then renders and
deploys. A partial sweep would stamp the whole page with a "last checked" time that is false for the
shards that never ran, so it is treated as a failure rather than as a partial success.

`--only shards/all.txt` keeps a module that was deleted upstream from lingering on a page that
claims it was just checked, and `--prune` deletes its stored entry as well: nothing sweeps a removed
module, so nothing would ever rewrite that file.

Pruning is only safe when the list is the whole tree, so `discover.sh` writes a `complete` marker
next to `all.txt` on the `--discover` path and `publish.sh` passes `--prune` only when it is there.
A `workflow_dispatch` run limited with the `modules` input produces a list of just those modules;
pruning against it would delete every other module's state. The marker is also why a local
`./ci/publish.sh` prunes after a full discovery and stays read-only after a manual one.

Two details keep a scheduled run useful rather than noisy:

- A `FAIL` verdict is a finding, not a build failure, so the run does not go red when a site breaks.
  The page is the result. Only a broken harness, a missing browser, or a missing Android SDK fails
  the job. `THROTTLED` is not a build failure either: a sweep that got rate-limited recorded what it
  could, and the page says so.
- A module that does not compile still gets a report: the harness records the missing layout as a
  failed load, so the module appears on the page with its reason instead of disappearing.

If every module fails to load, the job fails instead of publishing a page full of "failed to load":
a single site breaking is a finding, a broken toolchain is not.

The job commits the state to the `data` branch when something changed, never to `main`. That commit
cannot start another sweep, since `status.yml` has no push trigger; it also carries `[skip ci]` and is
pushed with `GITHUB_TOKEN`, which GitHub does not use to start workflows either. Because the store is
in git, the history of what broke when is the commit log, and a stale entry is one
`git log -p data -- <module>.json` away.

## Where the recorded state lives

The state moves every night. On `main` that would be a commit per night, and the harness's own
history would be buried under it, so the state lives on its own branch instead: **`data`**, whose
only content is the per-module reports at its root. `main` has no `data/` and ignores the directory.

The branches meet at `data/`:

- In CI, the `prepare`, sweep and publish jobs check the `data` branch out a second time at `data`,
  next to the harness. `prepare` needs it to balance the shards on the load each module produced
  last time; the shard writes into it and `collect.sh` reads the rewritten entries out of that
  checkout's `git status`; the publish job merges what the shards sent and commits it back.
- Locally, set the directory up as a worktree of the branch, so the harness finds a store where it
  expects one and `main` stays clean:

  ```bash
  git worktree add data data
  ```

  Then `--store data` works as before. `git worktree remove data` undoes it.

Because the branch was split from `main` with `git subtree split`, the history of the reports before
the split is still there: `git log data -- src-en-aurora.json` reaches back past it.

The page is published to `https://<owner>.github.io/kei-status/`. Enable it once under
**Settings -> Pages -> Build and deployment -> Source: GitHub Actions**.

## Running the steps locally

The shell steps live in `ci/` rather than in YAML, so the same commands can be run locally:

```
ci/discover.sh <extensions-source> <out-dir> <shard-count> [list-file]
ci/environment.sh [extensions-source]
ci/run-shard.sh <shard-file> [store-dir] [source-root]
ci/collect.sh <shard-id> <store-dir> <out-dir>
ci/apply.sh <incoming-dir> [store-dir]
ci/verify.sh <store-dir> <incoming-dir> <matrix.json>
ci/healthcheck.sh <store-dir> <incoming-dir>
ci/publish.sh <all-modules-file> [out-dir] [store-dir]
ci/commit.sh [store-dir] [branch] [message-prefix]
```

All of them require the store to be a git checkout of the `data` branch, since that is what tells
them which entries a run rewrote.

`healthcheck.sh` is designed for CI — without `GITHUB_STEP_SUMMARY` set it writes its summary to
`/dev/null` and prints only a one-liner — but it can summarize any store and incoming directory, so
it is usable locally to see what a sweep actually found.
