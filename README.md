# kei-status

Runs a Keiyoushi extension's real code on the JVM and reports whether its Mihon entry points still
work: connectivity, popular, latest, filters, chapter list, page list, and image URLs.

It is not a Mihon plugin. Extensions cannot call each other inside the app, so this is a separate
JVM tool that compiles a module from an `extensions-source` checkout, loads the resulting classes,
and stands in for the host.

## Contents

- [Quick start](#quick-start)
- [How it works](#how-it-works)
- [Statuses](#statuses)
- [Running checks](#running-checks)
- [Environment](#environment)
- [The published page](#the-published-page)
- [Continuous integration](#continuous-integration)
- [Known gaps](#known-gaps)
- [Layout](#layout)
- [Verdicts on real sources](#verdicts-on-real-sources)

## Quick start

### Requirements

- JDK 21+
- An `extensions-source` checkout next to this project (default `./extensions-source`)
- `extensions-source/local.properties` pointing at your Android SDK
- A Chromium-based browser for WebView sources, plus `xvfb-run` if the machine has no display.
  Chromium is started on first use and stopped at the end of the run; sources that never open a
  WebView do not pay for it. It runs on the display when one exists and headlessly otherwise, since
  a headless browser cannot clear every challenge (see [Cloudflare challenges](#cloudflare-challenges)).

### Run one extension

```bash
# 1. compile the extension (this also builds :core, themes, and libs it depends on)
cd extensions-source
./gradlew :src:en:aurora:compileDebugKotlin

# 2. record where its compiled classes live
./gradlew -I ../tools/harness.init.gradle.kts \
  -PharnessModule=:src:en:aurora :src:en:aurora:dumpHarnessClasspath

# 3. build and run the checker
cd ..
./gradlew :cli:installDist
./cli/build/install/cli/bin/cli --module :src:en:aurora
```

Step 2 only needs to be repeated when you switch modules or after `clean`.

## How it works

### What is real and what is simulated

| Layer | Status |
|---|---|
| Extension code (`src/<lang>/<name>`) | real, unmodified |
| `core/` (`KeiSource`, helpers, interceptors) | real, unmodified |
| `lib/`, `lib-multisrc/` themes | real, unmodified |
| OkHttp, Jsoup, coroutines | real |
| `HttpSource`, `NetworkHelper`, `SManga`, `SChapter`, `Page`, filters | reimplemented in `host-shim` |
| `android.*`, `androidx.preference` | reimplemented in `android-shim`, only what is needed |
| `android.webkit.WebView` | real Chromium, driven over the DevTools protocol (visible when a display exists, headless otherwise) |
| crypto providers | the JDK's, plus BouncyCastle for the transformations Android's copy supplies |
| `Bitmap`/`BitmapFactory`/`Canvas` | real image I/O via ImageIO (+ WebP plugin), `Canvas` on `Graphics2D` |
| Cloudflare challenge solving | attempted the way the app does it; a challenge that cannot be cleared is reported, not faked |

The two shims that matter most are `Bitmap` and `WebView`, because sources use them to *transform*
data rather than just fetch it:

- `Bitmap` wraps a `java.awt.image.BufferedImage`. `Canvas.drawBitmap(bitmap, srcRect, dstRect,
  paint)` maps exactly onto `Graphics2D.drawImage`, so a tile-descrambling loop's arithmetic runs
  unchanged. `recycle()` is faithful too: drawing a recycled bitmap throws, so real use-after-free
  bugs surface instead of being hidden.
- `WebView` owns one Chromium page target. Navigation, lifecycle callbacks, `evaluateJavascript`,
  `addJavascriptInterface`, `interceptRequest`, `blockImages` and cookies are all mapped onto
  DevTools commands, and callbacks are delivered on the main thread as on Android. `loadData` is
  served by intercepting the first navigation, so the base URL becomes the page origin and
  `localStorage` works without a network request.

### Loading an extension

The extension is loaded by a `URLClassLoader` whose parent is the harness class loader, so both
sides share one copy of OkHttp, coroutines, and the model interfaces. The child classpath is the
module's compiled classes, every compiled `core`/`lib`/`lib-multisrc` module, and any module root
that has an `assets/` tree (themes read i18n messages through the class loader as
`assets/i18n/...`, which is how the app merges assets).

A module that cannot link is reported as `UNSUPPORTED`/`FAIL` instead of aborting the run.

### Cloudflare challenges

A site that answers with a challenge is handled the way the app handles it, in two places:

- **Requests** — `CloudflareInterceptor` detects the challenge, opens the page in Chromium, lets the
  challenge settle, copies the cookies the browser earned into the OkHttp cookie jar, and replays
  the request once. `cf_clearance` is bound to the user agent that earned it, so the interceptor
  sends that user agent on every later request to the host too; a replay under any other identity is
  refused. If the challenge comes back, the run reports `BLOCKED`; the solver limits itself to one
  attempt per host per minute, so an extension this browser cannot clear fails fast instead of
  paying for a solve on every request.
- **WebViews** — a source that opens a challenged URL itself gets the same treatment, but only
  after the loaded page has been confirmed to be an interstitial, so ordinary pages are never
  clicked.

A response only counts as a barrier when Cloudflare says so: a `cf-mitigated: challenge` header, an
`x-amzn-waf-action` header for AWS WAF, or a page Cloudflare itself served, recognised by the
challenge bootstrap (`_cf_chl_opt`, `/cdn-cgi/challenge-platform/`) or by its error page
(`cf-error-details`). `cf-ray` alone is not evidence: it is on every response from a
Cloudflare-fronted site, including the site's own 403s. A site that answers 403 for its own reasons
— a chapter that needs an unlock, an age gate — keeps that answer, so the source reports what it
meant instead of the run inventing a challenge. A firewall block is still `BLOCKED`, but it is not
solved: no browser can turn a block into a puzzle.

Two properties of the live widget shape the clicker:

- The checkbox is an iframe inside a **closed** shadow root, so page JavaScript cannot see it:
  `document.querySelectorAll('iframe')` returns nothing while the checkbox is plainly on screen.
  It is located through the DevTools DOM domain, which pierces shadow roots.
- The widget is only clickable after Turnstile has booted, which is well after `readyState` is
  `complete` and even after the geometry is correct. A click before that is silently discarded and
  the interstitial stays up forever. Turnstile announces that moment with a `postMessage` event
  named `interactiveBegin`, so the click is gated on that event rather than on a timer.

The click is a real mouse event dispatched through the DevTools input domain. Each widget is
clicked at most once: Cloudflare issues a fresh widget id when it replaces a rejected widget, and
the caller remembers the ids it has clicked. The click is gated on the widget announcing that it
accepts input, so it is only ever spent on a widget that is actually waiting; pressing one a zone
would have passed on its own is itself a signal it rejects, measured as Turnstile `fail 600010`. A
challenge that renders no widget is left alone and reported as `BLOCKED`. Nothing is forged — if the
browser does not earn `cf_clearance`, the run says so.

The browser presents its own identity rather than being told to look like the app. A zone compares
the user agent with the rest of the browser, so an override contradicts it; and the clearance a
mismatched browser earns is bound to an identity the replay cannot reproduce. Measured against
`01.komikzoid.id`, one script, same machine and IP:

| browser | user agent | result |
| --- | --- | --- |
| headless | native | stuck at the checkbox |
| headless | app | stuck at the checkbox |
| visible | app | stuck at the checkbox; clicking it ends in Turnstile `fail 600010` |
| visible | native | cleared in about two seconds, no click |

So a display is not optional for challenged zones: `ci/run-shard.sh` runs the checker under
`xvfb-run` when it is installed, and the browser falls back to headless only when no display can be
created. Clearance earned by a visible browser is accepted by the replay when the replay presents
the same user agent, which is what the interceptor does for every request to the host.

Set `KEI_STATUS_NO_CF_SOLVE=1` to skip the attempt entirely and report the challenge immediately.

## Statuses

Every scenario, source and module ends in one of these:

| Status | Meaning |
|---|---|
| `PASS` | ran and met the contract |
| `WARN` | ran, but something is off (blank thumbnail, empty list, relative URL) |
| `FAIL` | exception, timeout, or broken contract |
| `SKIP` | the source does not support that entry point, its oldest chapter has to be unlocked too, or it runs against a server you host yourself |
| `UNSUPPORTED` | needs an Android class the harness does not provide |
| `BLOCKED` | anti-bot challenge; not the source's fault |
| `THROTTLED` | the site is rate-limiting the sweep (`HTTP 429`); the source was not checked |

`THROTTLED` exists because a sweep is a burst no reader produces. A module can declare one source per
language against a single site - MangaDot declares 108, all on `mangadot.net` - and the runner walks
them one after another, so the site's rate limit arrives as dozens of identical failures that say
nothing about the extension. It ranks below `BLOCKED` and above `UNSUPPORTED`: not the extension's
fault, but not a pass either, and it outranks `PASS` so the local checks cannot make an unchecked
source look healthy.

Two things keep it from spreading:

- **One bounded retry.** A single 429 is usually a burst limit that clears at once, so the scenario
  asks again after the site's own `Retry-After` (capped at 10s, 1s when the site sends none). Both
  attempts share the scenario's deadline, so a retry can never double the time a scenario may take.
- **A breaker per host, per module.** Once a host has answered 429 even after the retry, every later
  source on it is reported `SKIP` with that reason and makes no request at all, so the rest of the
  run neither repeats the failure nor keeps the throttle alive.

## Running checks

`cli --help` lists every option with its default; the examples below are the common shapes.

### Choosing what to run

A module list line is `<Gradle module> [extra flags]`, so a module with many language variants can
be kept to a slice: `:src:all:mangadex --lang en` runs one source instead of sixty-one.
`--discover` needs no list at all:

```bash
# every module, one JVM, one browser
./cli/build/install/cli/bin/cli --discover --store data

# the same work split across four machines
./cli/build/install/cli/bin/cli --discover --shard 1/4 --store data

# exactly what CI does: write the shard plan once, then run one shard of it
./ci/discover.sh extensions-source shards 4
./cli/build/install/cli/bin/cli --module-list shards/shard-1.txt --store data
```

### Keeping state between runs

`--store <dir>` keeps one file per module and compares the new run against it:

```bash
./cli/build/install/cli/bin/cli --module :src:en:aurora --store data
```

```
new module recorded; wrote data/src-en-aurora.json

state unchanged; data/src-en-aurora.json left alone

state changed (1 transition(s)); wrote data/src-en-aurora.json
  Aurora [en] pages: FAIL -> PASS
```

The file is only rewritten when the state actually moves, so a module that keeps passing produces
no diff. The store is meant to be committed: `git diff` inside it answers "what broke since the last
run", and the commit history is the timeline. Each stored report carries `comparedTo` (the run it
replaced) and `changes` (the transitions), which the page renders under the module heading.

The page itself is timestamped once, at the top, with when the run happened. A nightly job
refreshes the page every night while most modules keep the state they have had for weeks, so a
per-module "first run" or "since" label would be either stale or meaningless; only transitions are
shown per module, and only when there are any.

Fields that drift without meaning anything — scenario durations, the manga a site happened to
return, the checkout path, the clock — are excluded from the comparison. A scenario that keeps
failing but for a *different reason* still counts as a change, because the reason is what a reader
needs.

## Environment

| Variable | Effect |
|---|---|
| `KEI_STATUS_TRACE=1` | Prints stack traces for every classified failure. |
| `KEI_STATUS_WEBVIEW_TRACE=1` | Logs every DevTools event, request and evaluated script to stderr. This is the first thing to turn on when a WebView source misbehaves. |
| `KEI_STATUS_USER_AGENT` | Overrides the default User-Agent. |
| `KEI_STATUS_CHROME=/path/to/chrome` | Picks the browser binary. Without it the harness looks for `google-chrome-stable`, `google-chrome`, `chromium`, `chromium-browser` on `PATH`, then a few well-known install locations. |
| `KEI_STATUS_NO_CF_SOLVE=1` | Reports anti-bot challenges without trying to clear them, which is useful when you want to know whether a block is real rather than self-inflicted. |

Exit codes:

| Code | Meaning |
|---|---|
| `0` | no failures |
| `1` | at least one source failed |
| `2` | bad arguments |
| `3` | harness error |

## The published page

### Publishing

Several runs can be aggregated into one page:

```bash
# each run writes its own JSON report
./cli/build/install/cli/bin/cli --module :src:en:aurora \
  --output site/en-aurora/report.json

# aggregate every report under a directory into one static page
./cli/build/install/cli/bin/cli --site site --out site --title "Extension status"

# ... optionally publishing only the modules that exist right now, so a module deleted upstream
# does not linger on a page that claims it was just checked
./cli/build/install/cli/bin/cli --site site --out site --only modules.txt

# ... and, when that list is the whole tree, deleting the stored reports it does not name
./cli/build/install/cli/bin/cli --site data --out site --only modules.txt --prune
```

`--site` reads only the JSON files, so it needs no browser and no extension sources.

`--only` filters the page. `--prune` additionally deletes the stored entries the list does not name,
because a module removed upstream is never swept again: nothing rewrites its file, so filtering it
out of the page leaves the store growing one dead file per removal. It requires `--only`, since
without a list of what still exists every entry would go, and it deletes after the page is written
so a render that fails leaves the store untouched.

### What the page shows

- Plain HTML with no external assets, so it works from `file://` and on GitHub Pages. It filters by
  source, module, URL or message.
- Everything starts collapsed — the first screen is the list of modules. Opening a module shows its
  sources, which open in turn.
- The status tallies at the top are filters too: clicking `FAIL 2` narrows the page to those two
  sources, clicking it again clears the filter, and the text box narrows the result further.
  Filtering only changes which modules are listed — it opens nothing, so a status view is the same
  collapsed list as the page itself.
- Each extension is one collapsible block headed by the name the extension reports for itself
  (`AllManga`, not `:src:en:allanime`); clicking the header hides or shows the sources inside it,
  and the full module path stays available to the filter without being displayed.
- The `extensions-source` revision the reports were built from is shown once in the page header,
  where it links to that commit, instead of being repeated in every module header.
- An extension with a single source has nothing to keep apart, so it is one row: the header carries
  that source's language and URL, and the scenario table follows it directly — inside the same card
  a multi-source module puts around each of its sources, so the two shapes read the same when
  opened. Only a multi-source extension (one Gradle module shipping several sites, such as a
  `webtoons` bundle) gets a collapsible row per source.

## Continuous integration

### The two workflows

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
sweep itself without checking all 1,444.

### There is no module list

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

### Sharding

1,400+ modules do not fit in one job, so the discovered list is split into `20` shards (`shards`
input to change it) and each shard runs in its own job. Shards are independent: `fail-fast: false`,
so one bad shard does not cancel the rest.

The split is by **cost, not by module count**. A module is not a unit of work: `:src:all:xcomic`
declares 109 sources against one site while `:src:en:aurora` declares one, yet both are one module.
Splitting the list into equal slices also put every `:src:all:*` module - and they all sort to the
front - in the same two shards. Measured on the 2026-10-23 sweep, shards 1 and 2 held 10% of the
modules and 46% of the scenarios, and took 58 and 55 minutes against 15-24 for the other eighteen.

So the checker reads how many scenarios each module ran on its last sweep, out of the committed
store, and packs the heaviest first onto the emptiest shard. A module the store has never seen is
weighted like a typical one rather than as free, so a brand-new hundred-source aggregate cannot slip
onto a shard that happened to look empty. On the same tree that split puts every shard at 21-22
minutes instead of 58 at the worst, for the same total runner time.

A shard compiles its own modules in one Gradle invocation, so the shared modules (`:core`, `:lib:*`)
compile once instead of once per extension and compilation runs in parallel. What a shard does *not*
build is the harness: that is shared by every shard and is built once, in `prepare`.

### Build caching

Compiling an extension is the expensive part of a run and almost nothing changes upstream between
two nights. Each shard therefore keeps its own Gradle build cache (`~/.gradle/caches/build-cache-1`)
under a key scoped to that shard, plus one shared cache for downloaded dependencies.

The cache is per shard on purpose: a shard restores the same modules night after night, so its
entries are almost always useful to itself, and twenty jobs saving to one key would leave nineteen of
them discarded. Gradle's build cache is content-addressed, so a module that moves to another shard
after a cost change still restores from whichever cache compiled it. The key also carries the
upstream revision, so a second run on the same revision restores the same entry for free. With a
warm cache a module whose sources did not change is restored instead of recompiled, so a run spends
its time checking sites. A module that *did* change upstream misses the cache and is rebuilt, which
is exactly the wanted behaviour and needs no bookkeeping.

The harness itself is not an extension and does not depend on one, so it is built once in the
`prepare` job and handed to the shards as an artifact. Building it per shard was the same eighty
seconds of compilation paid twenty times over — about twenty minutes of runner time per run — and the
publish job used to pay it a twenty-first time. It is now built once and downloaded, which is also
why `publish` runs no Gradle at all.

Every shard checks out the revision `prepare` discovered the tree from, rather than asking for `main`
independently. Twenty checkouts of a moving branch can land on different commits, and then a module
the shard list names may not exist in the tree a shard actually compiled — which reads on the page as
a failed load. Pinning also keeps the build-cache key in agreement with the tree.

### What gets published

Each shard passes `--store data`, so results persist in the repository instead of living only in the
workspace of that one run. The store only rewrites a file when a module's state actually moves, so a
quiet night produces no commit; a module that moved gets one, and the page shows the transition. A
module that disappears upstream is the other case: `--prune` deletes its entry, and that night
commits a removal instead of a rewrite.

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

### Where the recorded state lives

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

### Running the steps locally

The shell steps live in `ci/` rather than in YAML, so the same commands can be run locally:

```
ci/discover.sh <extensions-source> <out-dir> <shard-count> [list-file]
ci/environment.sh [extensions-source]
ci/run-shard.sh <shard-file> [store-dir] [source-root]
ci/collect.sh <shard-id> <store-dir> <out-dir>
ci/apply.sh <incoming-dir> [store-dir]
ci/verify.sh <store-dir> <incoming-dir> <matrix.json>
ci/publish.sh <all-modules-file> [out-dir] [store-dir]
ci/commit.sh [store-dir] [branch] [message-prefix]
```

## Known gaps

### WebView and the browser

- WebView runs a real browser, but sites that gate on device fingerprinting still refuse it. A
  source that reports `FAIL` with the site's own "solve the captcha" message or a WebView timeout
  has been executed correctly — the site declined, the harness did not stub anything.
- `withNativeWebView` hands out a real `WebView` object, and `dispatchTouchEvent` forwards
  `MotionEvent`s to the browser as real touch input, so a captcha answered by tapping or swiping is
  attempted. `postVisualStateCallback` reports the page's next frame, which is when a touch starts
  being accepted; a page that never paints leaves it pending, and the source's own fallback takes
  over.
- `injectAtDocumentStart` falls back to the documented `onPageStarted` path: the Chromium glue is
  stubbed to advertise no optional WebView features, so document-start timing is unavailable.
  Sources that need a script to run before the page's own code may miss that window.

### Platform coverage

- Native QuickJS has no JVM build, so sources that evaluate JavaScript through it report
  `UNSUPPORTED` when a scenario needs it. A background prefetch that needs it prints a one-line note
  and does not fail the run.
- `android.graphics` covers `Bitmap`, `BitmapFactory`, `Canvas`, `Paint`, `Rect`/`RectF`, `Color`,
  `Matrix`, `ColorMatrix(ColorFilter)` and `Typeface`. Text layout (`StaticLayout`, `TextPaint`
  measurement) is not implemented, so sources that compose text onto images report `UNSUPPORTED`.
- `androidx.preference` covers the settings API surface extensions call, but nothing is rendered, so
  preference-driven behaviour is only exercised with default values.
- Crypto goes through the JVM's own providers plus BouncyCastle, which Android also bundles. Without
  it, `AES/CBC/PKCS7PADDING` (`lib/cryptoaes`) and `AES/CBC/ZEROBYTEPADDING` (Mangago) do not exist
  and the libraries swallow the failure, so the extension looked broken while it works on Android.
  BouncyCastle is appended after the JDK providers, so an algorithm that already resolved keeps
  resolving the same way.

### Network behaviour

- Header/cookie behaviour is close to the app but not identical, so a `PASS` here is strong evidence,
  not a guarantee.
- OkHttp sometimes reports `A connection to … was leaked. Did you forget to close a
  response body?`. That is an extension dropping a `Response` instead of closing it: a geo-block
  interceptor that throws mid-chain, an image interceptor that swaps the body without closing the
  original, a DDos-Guard probe that reads one header and returns. Everything the harness opens is
  closed, so this is the extension's bug, not the harness's. OkHttp logs it once per connection when
  it notices at GC time and then returns the connection to the pool, so it costs one line in the log
  and does not change a verdict.

### What the scenarios do and do not prove

- `filters` is checked once per extension class and site. A module that declares one source per
  language would otherwise fetch the same filter page once per language, and the background fetch
  failing never fails the source: `getFilterList()` falls back to the static list, so a broken
  dynamic filter fetch still reports `PASS`.
- `connectivity` is checked once per extension class and site, for the same reason: whether the home
  page answers is a property of the site, not of the language variant. The other sources report the
  same verdict with a note saying it was not probed again. HentaiHand declares 34 sources on one
  host and spent 255s of a 672s module asking the same URL 34 times; it now spends 1.3s. The cost is
  fidelity at the margin: a site that goes down mid-module still reports `PASS` for the sources
  checked after it, until the next run.
- `pages` asks for the **earliest numbered chapter**. A site's newest chapter is the one most often
  behind a paywall (a free promo chapter ages into a paid one, which is what happened to Comikey),
  while a first chapter is the sample a site tends to leave open. The pick is by `chapter_number`,
  not list position, because ordering is not part of the source contract. A chapter that still has
  to be unlocked is reported as `SKIP` with the source's own message, and the rest of the source is
  still checked. The alternative — walking the list until a free chapter appears — was rejected: it
  multiplies the page loads a sweep makes and can hang on the first chapter that never settles.
- A source that talks to a server the reader hosts themselves is reported `SKIP` without being
  probed: Komga (which has no address until one is typed into its settings), LANraragi, StashApp,
  Mango, FoolSlide Customizable and Bakkin Self-hosted all default to loopback. Every network
  scenario would only report `connection refused`, which says the server is absent, not that the
  extension is broken. The rule is the base URL itself — a loopback host, or no address at all on a
  configurable source — so a self-hosted extension added upstream is covered without touching a list.
- A source that gates its pages behind a Turnstile captcha is reported with the site's own verdict,
  not `UNSUPPORTED`: the harness provides the `Activity`/`Context` hierarchy core code needs, so the
  captcha is attempted. A widget that wants a click fails fast with "Captcha needs interaction, but
  the app isn't in the foreground" — there is no foreground — and a widget that never resolves ends
  in the source's own 2-minute WebView timeout, or in the harness's shorter `--timeout`, whichever
  comes first. Both are `FAIL` on the site, not a missing platform class.
- A Cloudflare challenge that only a visible browser can clear stays `BLOCKED` on a machine with no
  display: headless Chromium is detectable and the widget does not pass on its own. `ci/run-shard.sh`
  supplies a display with `xvfb-run`; the measurements are in
  [Cloudflare challenges](#cloudflare-challenges).

### Timeouts

`--timeout` is enforced from outside the extension, not by coroutine cancellation. Extension code
blocks: a source calls OkHttp's `execute()`, waits on a monitor, sleeps. `withTimeout` only takes
effect at a suspension point, so a blocked scenario ran to its own end instead — a 0.3s budget
around a 3s blocking call returned after 3.0s and did not report a timeout, and one module held a
shard for 27 minutes parked in `Http2Stream.waitForIo`. Each scenario therefore runs on its own
thread and the deadline interrupts it, which stops both blocking and suspending work. A block that
swallows the interrupt is abandoned rather than waited for: the report records the timeout and the
sweep moves on, and the thread is a daemon so it cannot hold the JVM open.

## Layout

```
android-shim/   android.* and androidx.preference stand-ins, plus the Chromium DevTools client
host-shim/      eu.kanade.tachiyomi.* and the keiyoushi host contract
engine/         loader, scenarios, checks, report, HTML page
cli/            argument parsing, console report, site publishing
ci/             shell steps the workflows call: discover, run a shard, verify, publish, commit
data/           checkout of the `data` branch: stored per-module reports (git-ignored here)
tools/          Gradle init script that records a module's compiled classes
.github/        the CI and status workflows
```

## Verdicts on real sources

A run over eleven modules, recorded while building the shims, produced `PASS`, `FAIL` and `BLOCKED`:

| Module | Verdict | Why |
|---|---|---|
| `:src:all:mangadex` | `PASS` | JSON API, At-Home image tokens, relative `imageUrl` |
| `:src:all:webtoons` | `PASS` | API + `CookieManager` |
| `:src:en:aurora` | `PASS` | plain HTML |
| `:src:en:asurascans` | `PASS` | madara theme; page images decoded end to end |
| `:src:en:athreascans` | `PASS` | mangathemesia theme + i18n assets |
| `:src:en:erisscans` | `PASS` | keyoapp theme |
| `:src:en:allanime` | `FAIL` | WebView runs; the site's SPA does not emit its page list within the source's 20s budget |
| `:src:en:batcave` | `FAIL` | DLE guard challenge is served to headless Chromium and not passed |
| `:src:en:alphamanga` | `BLOCKED` | AWS WAF challenge (`x-amzn-waf-action`) |
| `:src:en:comiccx` | `FAIL` | origin returns HTTP 522 |
| `:src:tr:hattorimanga` | `BLOCKED` | challenge clicked through; the zone refuses the replayed clearance |

`allanime` and `batcave` used to report `UNSUPPORTED` for a missing `android.webkit.WebView` and
`android.os.Looper`. They now run their real WebView code and fail for the site's reasons, which is
the honest answer.
