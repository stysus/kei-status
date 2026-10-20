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
--module <path>       Gradle module path, e.g. :src:en:aurora (required)
--source-root <dir>   extensions-source checkout (default: ./extensions-source)
--output <file>       write a JSON report
--timeout <seconds>   per-scenario timeout (default: 45)
--manga-limit <n>     manga sampled in the report (default: 3)
--page-limit <n>      pages probed (default: 3)
--no-images           skip downloading and decoding page images
--lang <codes>        only run these languages, comma separated (multi-source modules)
```

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
engine/         loader, scenarios, checks, report
cli/            argument parsing and the console report
tools/          Gradle init script that records a module's compiled classes
```

## Known gaps

- WebView runs a real browser, but sites that gate on device fingerprinting still refuse it. A
  source that reports `FAIL` with the site's own "solve the captcha" message or a WebView timeout
  has been executed correctly — the site declined, the harness did not stub anything.
- `withNativeWebView` hands out a real `WebView` object, but touch synthesis
  (`dispatchTouchEvent`, `MotionEvent`) is inert, so sources that solve a challenge by dragging a
  slider cannot succeed.
- `injectAtDocumentStart` falls back to the documented `onPageStarted` path, because the Chromium
  glue that would give it document-start timing is not present. Sources that need a script to run
  before the page's own code may miss that window.
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
- A Cloudflare challenge can be clicked through, but `cf_clearance` is bound to the client that
  earned it, so a zone that enforces that binding refuses the replayed request. Six sampled zones
  all earned the cookie and all still refused the replay; those extensions report `BLOCKED` after
  one attempt rather than retrying.
- One module per run; batching and baseline diffs are not implemented yet.

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
