# How kei-status works, and why it is built this way

Design notes: what is real, how an extension is loaded, and the measurements behind the choices.
For the day-to-day commands, see [usage.md](usage.md); for the sweep's automation, see
[ci.md](ci.md).

## What is real and what is simulated

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

## Loading an extension

The extension is loaded by a `URLClassLoader` whose parent is the harness class loader, so both
sides share one copy of OkHttp, coroutines, and the model interfaces. The child classpath is the
module's compiled classes, every compiled `core`/`lib`/`lib-multisrc` module, plus any third-party
jar the module resolves at run time (recorded by `tools/harness.init.gradle.kts`, because the app
gets those by bundling them into the extension jar while the harness loads a classes directory), and
any module root that has an `assets/` tree (themes read i18n messages through the class loader as
`assets/i18n/...`, which is how the app merges assets).

A module that cannot link is reported as `UNSUPPORTED`/`FAIL` instead of aborting the run.

## Decisions and measurements

This section records why the harness behaves the way it does, and the runs that settled each
question. The numbers are from the tree as it stood on the date named; they are evidence for a
decision, not a description of today's behaviour.

### Sharding balances cost, not module count

A module is not a unit of work: `:src:all:xcomic` declares 109 sources against one site while
`:src:en:aurora` declares one, yet both are one module. Splitting the list into equal slices also put
every `:src:all:*` module - and they all sort to the front - in the same two shards. Measured on the
2026-10-23 sweep, shards 1 and 2 held 10% of the modules and 46% of the scenarios, and took 58 and
55 minutes against 15-24 for the other eighteen.

So the checker reads how many scenarios each module ran on its last sweep, out of the committed
store, and packs the heaviest first onto the emptiest shard. A module the store has never seen is
weighted like a typical one rather than as free, so a brand-new hundred-source aggregate cannot slip
onto a shard that happened to look empty. On the same tree that split puts every shard at 21-22
minutes instead of 58 at the worst, for the same total runner time.

Store durations were the first weights and balanced the 58-minute shards to 21-22, but they are
frozen at each module's last status change, so a module that kept passing for a month was weighted
by a month-old number. The costs file makes the weights at most one night old: every run writes
wall-clock per module, the publish job unions the per-shard views into the store, and the next
prepare balances on that. The plan now also prints the projected load per shard, so a split is
judged before twenty runners are spent, and `run-shard.sh` prints the build/check time split so the
projection can be checked against reality.

### The build cache is per shard

Compiling an extension is the expensive part of a run and almost nothing changes upstream between
two nights. Each shard keeps its own Gradle build cache (`~/.gradle/caches/build-cache-1`) under a key
scoped to that shard, plus one shared cache for downloaded dependencies.

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

### Some scenarios are checked once per site

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

### `search` asks for a title the site itself advertises

The query is the first manga popular/latest just served, sent with the source's own filter list so
filter defaults apply exactly as they do in the app. The site must find the title it itself
advertises: an empty result, or one that misses the advertised manga, is a WARN — a lagging search
index is a normal site state, so it asks for a glance, not an incident. A search that throws or
times out still FAILs. This probes the flow where URL formats, query encoding and filter payloads
concentrate, which no other scenario touches. When popular and latest both return nothing, `search`
skips with the same note as chapters and pages: there was nothing to search for, and a source whose
first manga has no title skips for the same reason — an empty query is the harness probing badly.

### `pages` asks for the earliest numbered chapter

A site's newest chapter is the one most often behind a paywall (a free promo chapter ages into a
paid one, which is what happened to Comikey), while a first chapter is the sample a site tends to
leave open. The pick is by `chapter_number`, not list position, because ordering is not part of the
source contract. A chapter that still has to be unlocked is reported as `SKIP` with the source's own
message, and the rest of the source is still checked. The alternative — walking the list until a free
chapter appears — was rejected: it multiplies the page loads a sweep makes and can hang on the first
chapter that never settles.

### Timeouts are enforced from outside the extension

`--timeout` is enforced from outside the extension, not by coroutine cancellation. Extension code
blocks: a source calls OkHttp's `execute()`, waits on a monitor, sleeps. `withTimeout` only takes
effect at a suspension point, so a blocked scenario ran to its own end instead — a 0.3s budget
around a 3s blocking call returned after 3.0s and did not report a timeout, and one module held a
shard for 27 minutes parked in `Http2Stream.waitForIo`. Each scenario therefore runs on its own
thread and the deadline interrupts it, which stops both blocking and suspending work. A block that
swallows the interrupt is abandoned rather than waited for: the report records the timeout and the
sweep moves on, and the thread is a daemon so it cannot hold the JVM open.

### Linkage failures are classified by their cause

A link failure is only `UNSUPPORTED` when it names an `android.*`/`androidx.*` member the shim does
not supply, QuickJS, or a native library the host cannot load. `NoSuchMethodError`,
`NoSuchFieldError` and
`AbstractMethodError` carry the member in their message — `'byte[] android.util.Base64.decode(byte[],
int)'` — so the owner can be read off it, and a shim that provides `android.net.Uri` but not
`Uri.encode` is the same gap as a missing class: the extension is fine on a device, and calling it
broken would blame it for what the shim does not implement.

Everything else stays `FAIL`. A missing `keiyoushi.*` or `okhttp3.*` class means the harness
classpath is wrong, which is a harness bug and should not be filed under "needs a platform";
`IncompatibleClassChangeError` is left as `FAIL` for a different reason — nothing in it says which
side is wrong.

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
