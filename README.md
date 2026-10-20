# kei-status

Runs a Keiyoushi extension's real code on the JVM and reports whether its Mihon entry points still
work: connectivity, popular, latest, filters, chapter list, page list, and image URLs.

It is not a Mihon plugin. Extensions cannot call each other inside the app, so this is a separate
JVM tool that compiles a module from an `extensions-source` checkout, loads the resulting classes,
and stands in for the host.

## What is real and what is simulated

| Layer | Status |
|---|---|
| Extension code (`src/<lang>/<name>`) | real, unmodified |
| `core/` (`KeiSource`, helpers, interceptors) | real, unmodified |
| `lib/`, `lib-multisrc/` themes | real, unmodified |
| OkHttp, Jsoup, coroutines | real |
| `HttpSource`, `NetworkHelper`, `SManga`, `SChapter`, `Page`, filters | reimplemented in `host-shim` |
| `android.*`, `androidx.preference` | reimplemented in `android-shim`, only what is needed |
| `android.webkit.WebView` | real headless Chromium, driven over the DevTools protocol |
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

### Cloudflare challenges

A site that answers with a challenge is handled the way the app handles it, in two places:

- **Requests** — `CloudflareInterceptor` detects the challenge, opens the page in Chromium, clicks
  the widget once it accepts input, copies the cookies the browser earned into the OkHttp cookie
  jar, and replays the request once. The replay reuses the browser's user agent because
  `cf_clearance` is bound to it. If the challenge comes back, the run reports `BLOCKED`; the solver
  limits itself to one attempt per host per minute, so an extension this browser cannot clear fails
  fast instead of paying for a solve on every request.
- **WebViews** — a source that opens a challenged URL itself gets the same treatment, but only
  after the loaded page has been confirmed to be an interstitial, so ordinary pages are never
  clicked.

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
the caller remembers the ids it has clicked. A challenge that renders no widget is left alone and
reported as `BLOCKED`. Nothing is forged — if the browser does not earn `cf_clearance`, the run says
so.

A challenge that survives the replay is a real limit, not a missing feature: `cf_clearance` is bound
to the client that earned it, and a zone that enforces that binding refuses a replayed request even
with an identical user agent. Of six challenged zones sampled (`hattorimanga.net`, `onisaga.com`,
`manhuarmtl.com`, `lmtos.net`, `mangasbrasuka.org`, `astral-manga.fr`), all six earned
`cf_clearance` in about three seconds and all six still answered the replay with a challenge. The
solver is kept because zones that do not enforce the binding accept the replay, and because a
source's own WebView does pass some challenges with the same click.

Set `KEI_STATUS_NO_CF_SOLVE=1` to skip the attempt entirely and report the challenge immediately.

## Requirements

- JDK 21+
- An `extensions-source` checkout next to this project (default `./extensions-source`)
- `extensions-source/local.properties` pointing at your Android SDK
- A Chromium-based browser for WebView sources. Chromium is started headlessly on first use and
  stopped at the end of the run; sources that never open a WebView do not pay for it.

## Usage

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

Options:

```
--module <path>       Gradle module path, e.g. :src:en:aurora
--module-list <file>  run every module listed in <file>, one per line
--discover            run every module in the source tree
--shard <i>/<n>       run only shard i of n, so several jobs can split the work
--source-root <dir>   extensions-source checkout (default: ./extensions-source)
--output <file>       write a JSON report
--html <file>         write a standalone HTML page for this run
--store <dir>         keep one file per module, so the next run can report what changed
--timeout <seconds>   per-scenario timeout (default: 45)
--manga-limit <n>     manga sampled in the report (default: 3)
--page-limit <n>      pages probed (default: 3)
--no-images           skip downloading and decoding page images
--lang <codes>        only run these languages, comma separated (multi-source modules)
```

A module list line is `<Gradle module> [extra flags]`, so a module with many language variants can
be kept to a slice: `:src:all:mangadex --lang en` runs one source instead of sixty-one.
`--discover` needs no list at all:

```bash
# every module, one JVM, one browser
./cli/build/install/cli/bin/cli --discover --store data

# the same work split across four machines
./cli/build/install/cli/bin/cli --discover --shard 1/4 --store data
```

Publishing several runs as one page:

```bash
# each run writes its own JSON report
./cli/build/install/cli/bin/cli --module :src:en:aurora \
  --output site/en-aurora/report.json

# aggregate every report under a directory into one static page
./cli/build/install/cli/bin/cli --site site --out site --title "Extension status"

# ... optionally publishing only the modules that exist right now, so a module deleted upstream
# does not linger on a page that claims it was just checked
./cli/build/install/cli/bin/cli --site site --out site --only modules.txt
```

`--site` reads only the JSON files, so it needs no browser and no extension sources. The page is
plain HTML with no external assets: it works from `file://` and on GitHub Pages, filters by source,
module, URL or message. Everything starts collapsed — the first screen is the list of modules — and
opening a module shows its sources, which open in turn. The status tallies at the top are filters as
well: clicking `FAIL 2` narrows the page to those two sources, clicking it again clears the filter, and
the text box narrows the result further. Filtering only changes which modules are listed — it opens
nothing, so a status view is the same collapsed list as the page itself.
Each extension is one collapsible block headed by the name the extension reports for
itself (`AllManga`, not `:src:en:allanime`); clicking the header hides or shows the sources inside
it, and the full module path stays available to the filter without being displayed. The
`extensions-source` revision the reports were built from is shown once in the page header, where it
links to that commit, instead of being repeated in every module header.

An extension with a single source has nothing to keep apart, so it is one row: the header carries
that source's language and URL, and the scenario table follows it directly — inside the same card a
multi-source module puts around each of its sources, so the two shapes read the same when opened.
Only a multi-source extension (one Gradle module shipping several sites, such as a `webtoons`
bundle) gets a collapsible row per source.

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
no diff. The store is meant to be committed: `git diff data/` answers "what broke since the last
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


Environment:

- `KEI_STATUS_TRACE=1` prints stack traces for every classified failure.
- `KEI_STATUS_USER_AGENT` overrides the default User-Agent.
- `KEI_STATUS_CHROME=/path/to/chrome` picks the browser binary. Without it the harness looks for
  `google-chrome-stable`, `google-chrome`, `chromium`, `chromium-browser` on `PATH`, then a few
  well-known install locations.
- `KEI_STATUS_WEBVIEW_TRACE=1` logs every DevTools event, request and evaluated script to stderr.
  This is the first thing to turn on when a WebView source misbehaves.
- `KEI_STATUS_NO_CF_SOLVE=1` reports anti-bot challenges without trying to clear them, which is
  useful when you want to know whether a block is real rather than self-inflicted.

Exit codes: `0` no failures, `1` at least one source failed, `2` bad arguments, `3` harness error.

## Statuses

| Status | Meaning |
|---|---|
| `PASS` | ran and met the contract |
| `WARN` | ran, but something is off (blank thumbnail, empty list, relative URL) |
| `FAIL` | exception, timeout, or broken contract |
| `SKIP` | the source does not support that entry point |
| `UNSUPPORTED` | needs an Android class the harness does not provide |
| `BLOCKED` | anti-bot challenge; not the source's fault |

## How loading works

The extension is loaded by a `URLClassLoader` whose parent is the harness class loader, so both
sides share one copy of OkHttp, coroutines, and the model interfaces. The child classpath is the
module's compiled classes, every compiled `core`/`lib`/`lib-multisrc` module, and any module root
that has an `assets/` tree (themes read i18n messages through the class loader as
`assets/i18n/...`, which is how the app merges assets).

A module that cannot link is reported as `UNSUPPORTED`/`FAIL` instead of aborting the run.

## Layout

```
android-shim/   android.* and androidx.preference stand-ins, plus the Chromium DevTools client
host-shim/      eu.kanade.tachiyomi.* and the keiyoushi host contract
engine/         loader, scenarios, checks, report, HTML page
cli/            argument parsing, console report, site publishing
ci/             shell steps the workflows call: discover, run a shard, verify, publish, commit
data/           stored per-module reports, committed so runs can be compared
tools/          Gradle init script that records a module's compiled classes
.github/        the CI and status workflows
```

## Continuous integration

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

The shell steps live in `ci/` rather than in YAML, so the same commands can be run locally:

```
ci/discover.sh <extensions-source> <out-dir> <shard-count> [list-file]
ci/environment.sh [extensions-source]
ci/run-shard.sh <shard-file> [store-dir] [source-root]
ci/collect.sh <shard-id> <store-dir> <out-dir>
ci/apply.sh <incoming-dir> [store-dir]
ci/verify.sh <store-dir> <incoming-dir> <matrix.json>
ci/publish.sh <all-modules-file> [out-dir] [store-dir]
ci/commit.sh [store-dir] [message-prefix]
```

### There is no module list

`ci/discover.sh` reads the tree. `extensions-source` registers every `src/<lang>/<name>` directory
as a Gradle module (`loadAllIndividualExtensions()` in `extensions-source/settings.gradle.kts`), and
every real extension carries a `build.gradle.kts` — that file is what tells an extension from a
stray directory. An extension added upstream is checked by the next run without anyone editing a
file here; a hand-written list would go stale the day after it was written.

`--module-list` still exists for a one-off run, and the `modules` input on the `workflow_dispatch`
trigger feeds one in without committing it.

### Sharding

1,400+ modules do not fit in one job, so the discovered list is split into contiguous, evenly sized
shards (`20` by default, `shards` input to change it) and each shard runs in its own job. Shards are
independent: `fail-fast: false`, so one bad shard does not cancel the rest.

A shard compiles its own modules in one Gradle invocation, so the shared modules (`:core`, `:lib:*`)
compile once instead of once per extension and compilation runs in parallel. What a shard does *not*
build is the harness: that is shared by every shard and is built once, in `prepare`.

### Build caching

Compiling an extension is the expensive part of a run and almost nothing changes upstream between
two nights. Each shard therefore keeps its own Gradle build cache (`~/.gradle/caches/build-cache-1`)
under a key scoped to that shard, plus one shared cache for downloaded dependencies.

The cache is per shard on purpose: a shard always compiles the same modules, so its entries are only
useful to itself, and twenty jobs saving to one key would leave nineteen of them discarded. The key
also carries the upstream revision, so a second run on the same revision restores the same entry for
free. With a warm cache a module whose sources did not change is restored instead of recompiled, so
a run spends its time checking sites. A module that *did* change upstream misses the cache and is
rebuilt, which is exactly the wanted behaviour and needs no bookkeeping.

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
quiet night produces no commit; a module that moved gets one, and the page shows the transition.

Shards upload only the files they rewrote, plus a marker recording that the shard finished. The
publish job merges those, refuses to publish unless **every** shard reported in, then renders and
deploys. A partial sweep would stamp the whole page with a "last checked" time that is false for the
shards that never ran, so it is treated as a failure rather than as a partial success.

`--only shards/all.txt` keeps a module that was deleted upstream from lingering on a page that
claims it was just checked.

Two details keep a scheduled run useful rather than noisy:

- A `FAIL` verdict is a finding, not a build failure, so the run does not go red when a site breaks.
  The page is the result. Only a broken harness, a missing browser, or a missing Android SDK fails
  the job.
- A module that does not compile still gets a report: the harness records the missing layout as a
  failed load, so the module appears on the page with its reason instead of disappearing.

If every module fails to load, the job fails instead of publishing a page full of "failed to load":
a single site breaking is a finding, a broken toolchain is not.

The job commits `data/` back to `main` when something changed. That commit cannot start another
sweep, since `status.yml` has no push trigger; it also carries `[skip ci]` and is pushed with
`GITHUB_TOKEN`, which GitHub does not use to start workflows either. Because the store is in git,
the history of what broke when is the commit log, and a stale entry is one
`git log -p data/<module>.json` away.

The page is published to `https://<owner>.github.io/kei-status/`. Enable it once under
**Settings -> Pages -> Build and deployment -> Source: GitHub Actions**.

## Known gaps

- WebView runs a real browser, but sites that gate on device fingerprinting still refuse it. A
  source that reports `FAIL` with the site's own "solve the captcha" message or a WebView timeout
  has been executed correctly — the site declined, the harness did not stub anything.
- `withNativeWebView` hands out a real `WebView` object, but touch synthesis
  (`dispatchTouchEvent`, `MotionEvent`) is inert, so sources that solve a challenge by dragging a
  slider cannot succeed.
- `injectAtDocumentStart` falls back to the documented `onPageStarted` path: the Chromium glue is
  stubbed to advertise no optional WebView features, so document-start timing is unavailable.
  Sources that need a script to run before the page's own code may miss that window.
- Native QuickJS has no JVM build, so sources that evaluate JavaScript through it report
  `UNSUPPORTED` when a scenario needs it. A background prefetch that needs it prints a one-line note
  and does not fail the run.
- `android.graphics` covers `Bitmap`, `BitmapFactory`, `Canvas`, `Paint`, `Rect`/`RectF`, `Color`,
  `Matrix`, `ColorMatrix(ColorFilter)` and `Typeface`. Text layout (`StaticLayout`, `TextPaint`
  measurement) is not implemented, so sources that compose text onto images report `UNSUPPORTED`.
- `androidx.preference` covers the settings API surface extensions call, but nothing is rendered, so
  preference-driven behaviour is only exercised with default values.
- Header/cookie behaviour is close to the app but not identical, so a `PASS` here is strong evidence,
  not a guarantee.
- `filters` is checked once per extension class and site. A module that declares one source per
  language would otherwise fetch the same filter page once per language, and the background fetch
  failing never fails the source: `getFilterList()` falls back to the static list, so a broken
  dynamic filter fetch still reports `PASS`.
- A Cloudflare challenge can be clicked through, but `cf_clearance` is bound to the client that
  earned it, so a zone that enforces that binding refuses the replayed request. Six sampled zones
  all earned the cookie and all still refused the replay; those extensions report `BLOCKED` after
  one attempt rather than retrying.

## Verdicts on real sources

A run over ten modules, recorded while building the shims, produced all four outcomes:

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
