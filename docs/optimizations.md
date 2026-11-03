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
- AVIF decodes through the `avif-imageio` ImageIO plugin on the classpath (natives inside the
  jar, same pattern as `webp-imageio`); a source serving AVIF is healthy here instead of a
  permanent false-alarm WARN. HEIF still has no JVM plugin and keeps its honest note.
  `ImageDecodeNoteTest` pins the decode with a real AVIF fixture.
- Duplicate-url notes name their urls in sorted order, so a site that returns its list in a
  different order does not churn the store with note-text rewrites
  (`ChecksTest` pins order independence).
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
  `throttledHosts` in reports and the CLI summary.
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

## User-Agent bump (Nov 2026)

Default UA raised from Chrome/126 (Jun 2024) to Chrome/156 and switched to Chrome's reduced
form (`Android 10; K`) after 4 Madara ar modules (mangalek, mangalink, mangalionz, mangaspark)
received HTTP 413 on popular/latest POST to `wp-admin/admin-ajax.php` in the Nov 2 CI sweep
(commit 1ba459f). The 413 did not reproduce from a local IP with either UA, so the trigger is
likely the combination of a stale Chrome major version with a datacenter IP — a pattern WAFs
commonly reject with non-standard status codes. `KEI_STATUS_USER_AGENT` remains the escape
hatch. Success criterion: 413 does not recur on the next CI sweep.

## How to re-verify

Each family's changes carry their own pins: `ModuleCostsTest`, `ReportStoreTest` (vanished
source, key identity, roll-up mismatch, scenario order), `ModuleListTest` (median fallback),
`ExitCodeTest` (BLOCKED/THROTTLED/UNSUPPORTED/DOWN stay green), `DeadlineTest` (caller cancellation),
`CookieManagerTest` (leading-dot domains), `SummaryFormatTest` (grep contract). End to end:
`./kei-status run :<module> --store /tmp/kei-status-verify`, then `./ci/healthcheck.sh` on
any store to see the summary it would render.

## Chromium boundary copies guarded (Nov 2026)

`android-shim` re-declares the `org.chromium.support_lib_boundary` interfaces by hand — core
marks them `internal`, and the extensions-source tree is a runtime checkout rather than a build
dependency, so they cannot be imported. The harness's `URLClassLoader` asks its parent first,
and the parent owns the shim, which means the shim's copies are the classes that load while
core's own sit shadowed: a member core changed or added would surface as `NoSuchMethodError`
mid-sweep, not at build time. `tools/core-boundary-contract.py` compares the JVM member names
of every interface on both sides (a Kotlin property through its getter/setter name, `isX`
included) and runs in the sweep's `prepare` job next to the host-shim contract snapshot, so a
drifted copy stops the sweep before the shards start. Its name mapping is pinned by
`--self-test`; the comparison itself was verified by planting a fake member and watching the
check fail.

## Search miss re-tried and matched by title (Nov 2026)

The sweep's search WARNs were dominated by searches that work - the same sources answer normally in
the app - and two harness-side causes explained most of them. First, the hit test compared the
popular entry's URL exactly, but sites serve one title under several gallery ids, so search returned
the right manga under an id popular never mentioned. Second, the query kept the punctuation a
listing title carries, and gallery search engines read it as syntax - `!` as exclusion, parentheses
as grouping - so a query like `Mojo!` or `(C92) Lust Vampire (Fate/Grand Order)` matched nothing
though the manga is trivially findable. The hit now also accepts the advertised title once both
sides are stripped the way the query is, and a miss is retried once with the query reduced to
letters and digits. A retry that finds the title passes with a note saying so; one that misses both
ways keeps the WARN with both notes. A retry that never comes back is the retry's own verdict — its
FAIL, DOWN or THROTTLED stands, with the first attempt's notes kept for context. Pinned by
`SearchRetryTest` and `SearchQueryTest`.

## Throttle retry doubled (Nov 2026)

The sweep data showed every throttle came from sites that rate-limit the runner's datacenter IP -
mostly Blogger, which throttles across all of `*.blogspot.com` from one request - rather than from
the sweep bursting one host. Those sites pass from a residential IP and passed on earlier sweeps,
so the verdicts were transient and the fix was patience, not pacing: pacing cannot prevent the
first 429 when the threshold is the IP's reputation, and waiting longer between requests would
stretch every shard to serve sites that will answer 429 regardless. The retry went from one to two
(1s then 5s, `Retry-After` preferred and capped at 10s as before), still inside the scenario's
deadline, so a throttle that outlasts all three attempts stays `THROTTLED`. Pinned by
`ThrottleRetryTest`.

## Diagnosis reclassification (Nov 2026)

Five categories of FAIL reclassified in one batch:

| Category | New status | Rationale |
|---|---|---|
| HTTP 403 (no anti-bot) | BLOCKED | Mostly Japanese geo-blocks; the rest are server-side rejections. The reason text is deliberately cautious: a pages/chapters 403 can be hotlink protection (Referer mismatch), so it says "typically geo-blocking or bot rejection" rather than "not an extension bug". |
| HTTP 500-504, 520-530 | DOWN | Origin server errors. 520-530 are Cloudflare-specific; 500-504 are standard. Range stops at 504 + 520-530 (not 505-519, which includes 511 Network Authentication Required — a proxy/captive portal, not "server down"). |
| SSL/TLS errors | DOWN | Expired certs, hostname mismatches, peer shutdowns. Detection by exception type (`SSLException` hierarchy + `CertificateException`), with fallback on message keywords (`SSL`, `certificate`, `NotAfter`). |
| Search-not-supported throws | SKIP | Extensions that throw `Exception("No search")` or similar. Only matches non-`RuntimeException` exceptions with specific message patterns; a validation message like "Search query must be at least 3 characters" remains FAIL. |
| Network-level failures | DOWN | Connect and read timeouts, connection refused, DNS resolution failures. Classified by exception type — `SocketTimeoutException`, `ConnectException`, `UnknownHostException`, `NoRouteToHostException` — never by message. Historical note: the Nov 2 DNS wave (93 sources, 34 modules, all FAIL) would read as DOWN if it recurred, not a wall of false regressions. `java.util.concurrent.TimeoutException` is deliberately NOT matched — that is the extension's own timeout and stays FAIL. |

Counts per category are intentionally omitted: they were true only for the sweep that
motivated the change and would read as a stale baseline later. Check the store or the
classification date range if the magnitude ever matters again.

**CI churn note:** The first sweep after this change shows a burst of one-time classification
transitions that triggers the `REGRESSION_BURST` warning in `ci/healthcheck.sh` (line 62:
`to in ("FAIL", "BLOCKED")` counts FAIL→BLOCKED as regression). This is classification churn,
not a real regression. The burst warning self-resolves after one sweep because the new
baselines stabilise.
