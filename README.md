# kei-status

Runs a Keiyoushi extension's real code on the JVM and reports whether its Mihon entry points still
work: connectivity, popular, latest, filters, chapter list, page list, and image URLs.

It is not a Mihon plugin. Extensions cannot call each other inside the app, so this is a separate
JVM tool that compiles a module from an `extensions-source` checkout, loads the resulting classes,
and stands in for the host.

> **Not affiliated with Keiyoushi or Mihon.** This is an independent project. It is not endorsed by,
> sponsored by, or maintained by either, and it ships no extension code: the extension sources are
> checked out at build time, from their own repository, under their own license. See [NOTICE](NOTICE).

## Quick start

### Requirements

- JDK 21+
- An `extensions-source` checkout next to this project (default `./extensions-source`)
- An Android SDK — the extension build needs it. Set `ANDROID_HOME` the first time and the wrapper
  records it in `extensions-source/local.properties`.
- A Chromium-based browser for WebView sources, plus `xvfb-run` if the machine has no display.
  Chromium is started on first use and stopped at the end of the run; sources that never open a
  WebView do not pay for it. It runs on the display when one exists and headlessly otherwise, since
  a headless browser cannot clear every challenge (see [docs/cloudflare.md](docs/cloudflare.md)).

```bash
git clone --depth 1 https://github.com/keiyoushi/extensions-source
```

### Run one extension

```bash
./kei-status run :src:en:aurora
```

That is the whole thing. The wrapper compiles the module, records where its compiled classes and
run-time jars landed, builds the harness if it is stale, and runs the check. Anything after the
module goes straight to the checker:

```bash
./kei-status run :src:all:mangadex --lang en --store data
```

```
kei-status — :src:en:aurora (extension 9134a5162)

Aurora [en] https://comicaurora.com
  PASS        metadata       1ms
  PASS        connectivity   1672ms
  PASS        popular        10844ms
  SKIP        latest         0ms  supportsLatest = false
  PASS        filters        0ms
  PASS        chapters       2051ms
  PASS        pages          823ms
  -> PASS


1 module(s) checked, 0 changed, 0 transition(s)
```

Every flag after the module goes straight to the checker; `./kei-status --help` shows its own option
list. The individual steps the wrapper replaces are in [docs/usage.md](docs/usage.md).

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

## Limitations

What this cannot tell you is as important as what it can.

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
- A Cloudflare challenge that only a visible browser can clear stays `BLOCKED` on a machine with no
  display: headless Chromium is detectable and the widget does not pass on its own.
  `ci/run-shard.sh` supplies a display with `xvfb-run`.

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

### What a `PASS` does not prove

- `filters` and `connectivity` are checked once per extension class and site, so a source checked
  after a site went down mid-module still reports the earlier verdict.
- `pages` asks for the earliest numbered chapter, so a site that broke only on its newest chapter
  still passes.
- A source that gates its pages behind a Turnstile captcha is reported with the site's own verdict,
  not `UNSUPPORTED`: the harness provides the `Activity`/`Context` hierarchy core code needs, so the
  captcha is attempted.
- A source pointed at a server the reader hosts themselves is reported `SKIP` without being probed.

The measurements and the rejected alternatives behind each of these are in
[docs/design.md](docs/design.md).

## Documentation

| | |
|---|---|
| [docs/usage.md](docs/usage.md) | running a subset, storing state, environment variables, exit codes, publishing the page |
| [docs/ci.md](docs/ci.md) | the two workflows, sharding, caching, the `data` branch |
| [docs/cloudflare.md](docs/cloudflare.md) | how anti-bot challenges are detected and cleared |
| [docs/design.md](docs/design.md) | what is real and what is simulated, and why it is built this way |
| [docs/optimizations.md](docs/optimizations.md) | the October 2026 optimization campaign: what changed, what it measured, what was rejected |

## Layout

```
android-shim/   android.* and androidx.preference stand-ins, plus the Chromium DevTools client
host-shim/      eu.kanade.tachiyomi.* and the keiyoushi host contract
engine/         loader, scenarios, checks, report, HTML page
cli/            argument parsing, console report, site publishing
ci/             shell steps the workflows call: discover, run a shard, verify, publish, commit
data/           checkout of the `data` branch: stored per-module reports (git-ignored here)
tools/          Gradle init script that records a module's compiled classes
docs/           the longer-form documentation linked above
kei-status      the wrapper: compile a module, record its classpath, run the checker
.github/        the CI and status workflows
```

## License

[Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for attribution and the affiliation disclaimer.
