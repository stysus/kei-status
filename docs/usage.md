# Running checks

`cli --help` lists every option with its default; the examples below are the common shapes.

## The short version

```bash
./kei-status run :src:en:aurora
```

That compiles the extension, records where its classes and run-time jars landed, builds the harness
if it is stale, and runs the check.

## The same thing by hand

Useful when the wrapper's assumption is the thing that broke. Step 2 only needs repeating when you
switch modules or after `clean`.

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

Step 2 writes `extensions-source/src/en/aurora/build/harness-classpath.json`. A missing file is the
most common first failure, and the checker's own error message names the command that writes it.

## Choosing what to run

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

The wrapper passes anything after the module straight to the checker, so
`./kei-status run :src:all:mangadex --lang en` works too.

## Keeping state between runs

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
no diff. The store is meant to be committed (on its own branch, see [ci.md](ci.md#where-the-recorded-state-lives)):
`git diff` inside it answers "what broke since the last run", and the commit history is the timeline.
Each stored report carries `comparedTo` (the run it replaced) and `changes` (the transitions), which
the page renders under the module heading.

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

## The published page

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

## What the page shows

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
