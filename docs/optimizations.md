# Optimization campaign — October 2026

Commits `164d365..4b14ac5` (90 on main; 86 harness commits — the other 4 are stray
store-record commits). 26 aspect families reviewed; 92 suggestions accepted, 8 rejected. Each
family names what changed and why; measured outcomes cite
the sweep that proved them. This is a historical record, not a manual — the reasoning that still
applies lives in [design.md](design.md), and this links rather than repeats it.

## Shard balancing

- Cost-based split (was: equal slices): worst shard 58 min → 21-22 projected; now weighted by
  nightly wall-clock from the `costs` file, not store durations frozen at the last status change
  (see [design.md](design.md#sharding-balances-cost-not-module-count)).
- `projected load` printed at plan time; `shard timing: build/check` per shard, so a split can be
  judged before twenty runners are spent and checked against reality afterwards.

## Per-site probe dedup

- `filters` and connectivity once per (class, site): HentaiHand 255 s of 672 s spent re-probing
  34 variants → one probe; MangaDot 108× likewise
  (see [design.md](design.md#some-scenarios-are-checked-once-per-site)).

## Deadline

- Scenario deadline now enforced against blocking work (the original 27-minute-stall bug):
  interrupt-ignoring blocks are cut, suspending waits are not
  (see [design.md](design.md#timeouts-are-enforced-from-outside-the-extension)).
- Caller cancellation cancels the underlying work and rethrows instead of leaving a scenario
  running behind a dead run.

## Scenario correctness

- `pages` checks the oldest locked chapter, not the newest — the latest is where new chapters
  appear, so the old choice reported healthy sites as failing
  (see [design.md](design.md#pages-asks-for-the-earliest-numbered-chapter)).
- Image decode failures record the image format and byte size in the note, so "broken image"
  findings are diagnosable from the page alone.
- `probeHome` accepts an empty body as a loaded page instead of demanding content the site may
  legitimately not have.

## Verdict fidelity

- Login walls, geo-restrictions, and self-hosted sources get their own verdicts instead of
  masquerading as FAIL.
- A browser that cannot start is UNSUPPORTED, not a site failure; Cloudflare interception is
  detected from the server header, so a cleared challenge is not misread as a challenge page.
- `CancellationException` is captured as a finding instead of crashing the scenario runner.
- A 429 on the sweep's own probes (`probeHome`, `probeImages` resolve and download) throws
  `HttpException` through `Response.throwIfThrottled()` instead of becoming a note, so the scenario
  is THROTTLED and the host's breaker closes — previously only extension-thrown 429s counted.
  `ThrowIfThrottledTest` pins the response-side guard; the two catch-side rethrow guards are
  one-line `is HttpException && code == 429` checks whose downstream classification
  (`ChecksTest` pinning `HttpException(429)` → THROTTLED) is already pinned, so they carry no
  test of their own.
- A page list that shares one url (Comix's index-disambiguated pages) is not a duplicate finding.

## Browser lifecycle

- `Page.close()` tears the DevTools session down; `Chrome.newPage` takes a page factory so pages
  do not share a torn-down session; each page gets its own port.
- A page whose DevTools socket dies is recorded and closed, not polled forever.

## Concurrency

- `FileSharedPreferences` synchronized, `CookieManager` domain-aware, `Looper` message queue
  got `enqueueDelayed`, shared state moved onto `ConcurrentHashMap` — the stand-ins were written
  single-threaded and the harness is not.
- `Page.send()` double-checks `closed` after enqueueing, closing the race with `onFailure`.
- `WebView` state is volatile where a reader thread can see a stale value.

## Cookies

- `InMemoryCookieJar` keys by domain; the shim `CookieManager` stores `Domain=` cookies in a
  domain bucket with a targeted parent lookup, parses the header in one pass, and pushes to the
  browser in a single batched CDP call.
- `syncCookiesFromBrowser` is scoped to the source's own domain instead of importing everything.

## Resource handling

- `BitmapFactory` reads image dimensions without decoding; bulk bitmap operations replaced
  per-pixel work; `probeImages` runs concurrently.
- `ReportStore` writes via `writeAtomically`, so a crash mid-write cannot corrupt an entry.

## Caching

- `SourceLoader` caches loaded classes per module; `gitCommit()` is cached per source root with a
  10 s timeout; the deadline executor is shared instead of allocated per call.

## Observability

- Summary line gained verdict breakdown and wall-clock duration; per-module `slowest:` line;
  `throttledHosts` in reports and on the page.
- Publish job renders a `$GITHUB_STEP_SUMMARY` with verdict distribution and transitions, and
  warns on a regression burst (`ci/healthcheck.sh`).

## CLI robustness

- Mutually exclusive flags rejected up front, numeric flags validated, `--output`/`--html`
  guarded against writing into the store.

## Diagnosis

- `diagnose()` and `reason()` merged into one pass, fixing an ordering bug where the recorded
  reason contradicted the verdict (e.g. `ActivityNotFoundException` vs `needsWebViewSession`).

## CI pipeline

- `commit.sh` aborts leftover rebase state and retries the push on a rebase; `costs` is counted
  separately so a quiet night reads `measured costs updated`, not `1 changed`.
- `SummaryFormatTest` pins the summary-line grep contract `run-shard.sh` depends on.
- The `costs` file travels per shard (`costs-<shard>`) and is unioned by `apply.sh` — before
  this, measured timings were collected every night and thrown away.

## Build and dependencies

- Gradle configuration cache on; the two builds in `kei-status` run in parallel.
- `serialization-protobuf` moved to `engine`, `injekt-core` to `host-shim`; five android-shim
  dependencies narrowed from `api` to `implementation`.

## Micro-optimizations

- Byte-level image-format sniffing; cached `LANG_PATTERN`, `WHITESPACE`, `COOKIE_ATTRIBUTES`;
  `configFor` skips `Options.parse` when a module carries no flags; one `Json` instance shared
  across the DevTools layer; `continueRequest` receives pre-parsed headers.

## Testing

- The suite grew from 80 tests in 10 files to 328 in 48, adding:
  store diffing (vanished sources, roll-up mismatch, scenario-order immutability), module costs
  (file over store, corrupt fallback, median pricing), exit codes, deadline cancellation, cookie
  domains, and the summary-format grep contract.

## Rejected, and why

Kept here so the same proposal is not re-litigated:

- `api(libs.kotlin.stdlib)`: intentional transitive exposure, matches the all-`api` shim pattern.
- `hostAndParents` / `sourceType` caches: the operation costs ~200-500 ns, the same order as a
  cache lookup — textbook premature optimization.
- Concurrency/parallelism ×4: races and orderings are deliberate invariants, not defects.
- ChallengeSolver pure-logic extraction: refactor would break the shadow-lookup contract; the
  browser-gated tests run in CI where Chrome exists.

## How to re-verify

Each family's changes carry their own pins: `ModuleCostsTest`, `ReportStoreTest` (vanished
source, key identity, roll-up mismatch, scenario order), `ModuleListTest` (median fallback),
`ExitCodeTest` (BLOCKED/THROTTLED/UNSUPPORTED stay green), `DeadlineTest` (caller cancellation),
`CookieManagerTest` (leading-dot domains), `SummaryFormatTest` (grep contract). End to end:
`./kei-status run :<module> --store /tmp/kei-status-verify`, then `./ci/healthcheck.sh` on
any store to see the summary it would render.
