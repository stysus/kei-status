# Codebase Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the eight places where one rule is written twice in the harness, so a change to the rule can only be made in one place.

**Architecture:** Every task deletes a second copy of a rule and points the surviving copy at the first. Nothing new is introduced except one CLI output mode (Task 7) and one composite action (Task 8), both of which exist to *remove* a duplicated rule rather than add a layer. No behaviour changes: the statuses, the store bytes, the page and the shard lists must come out identical.

**Tech Stack:** Kotlin 2.4 (JVM 21), Gradle 9.8, JUnit 4, bash, GitHub Actions.

**Spec:** the eight findings in the consolidation review of 2026-10-23 (this document's task list *is* the spec).

## Global Constraints

- Never modify anything under `extensions-source/`.
- Do not run the `status` workflow.
- Do not commit unless the user asks; each task ends with a green build, not a commit.
- `./gradlew build` must pass after every task, and `:cli:installDist` must be re-run before any CLI smoke test.
- Stored report bytes must not change: `data/*.json` is the regression baseline.
- Local shell is zsh; quote `--include` globs.

## Review Focus

- A store path computed from a hardcoded `data` while `--store` points elsewhere (Task 3) — the bug being fixed; a `--store out` run must print `out/…`, not `data/…`.
- A new `Status` value silently missing from the page's tally chips (Task 2) — the compiler cannot catch a hand-written list.
- `--timeout` reaching the harness as 45 from three different places (Task 4) — a changed default must move once.
- A manual module list that carries per-entry flags (`:src:all:mangadex --lang en`) surviving Task 7's plan writer intact, because `harness.init.gradle.kts` strips them and `run-shard.sh` must still pass them through.
- A shard whose Gradle dependency cache key diverges from `prepare`'s (Task 8) — a miss is silent, only slower.

---

### Task 1: Delete the two dead duplicates

**Files:**
- Modify: `cli/src/main/kotlin/kei/status/cli/Main.kt:221-222`
- Modify: `engine/src/main/kotlin/kei/status/engine/Report.kt:114-116`

**Interfaces:**
- Consumes: nothing.
- Produces: nothing.

- [ ] **Step 1: Confirm both are unreferenced**

Run: `grep -rn "\.slug\b" --include='*.kt' . | grep -v /build/ | grep -v extensions-source` and `grep -rn "decode" cli/src/main/kotlin/`
Expected: only the declarations themselves.

- [ ] **Step 2: Delete `RunReport.slug`** and its KDoc from `Report.kt`. `ReportStore.slugOf()` keeps the rule; it is the one that is actually used.

- [ ] **Step 3: Delete `Main.decode`** and its comment. `ReportStore.decode` keeps the rule.

- [ ] **Step 4: Verify**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, 96 tests, 0 failures.

---

### Task 2: Derive the status order from `Status.severity`

**Files:**
- Modify: `engine/src/main/kotlin/kei/status/engine/ReportHtml.kt:15-22`
- Test: `engine/src/test/kotlin/kei/status/engine/ReportHtmlTest.kt`

**Interfaces:**
- Consumes: `Status.severity` (`engine/.../Report.kt:21`).
- Produces: `ReportHtml.STATUS_ORDER` stays private; nothing outside the object reads it.

- [ ] **Step 1: Write the failing test**

Add to `ReportHtmlTest`:

```kotlin
@Test
fun `the tally chips are ordered worst first`() {
    val html = ReportHtml.site(Status.entries.map { report(":src:en:$it", it) })
    val filters = Regex("""data-filter="(\w+)"""")
        .findAll(html)
        .map { it.groupValues[1] }
        .toList()

    assertEquals(
        listOf("fail", "blocked", "unsupported", "warn", "pass", "skip"),
        filters,
    )
}
```

- [ ] **Step 2: Run it to confirm it passes before the change**

Run: `./gradlew :engine:test --tests '*ReportHtmlTest*'`
Expected: PASS. The test pins today's order so the refactor cannot move it.

- [ ] **Step 3: Replace the hand-written list**

In `ReportHtml.kt`, replace the six-entry `listOf(...)` with:

```kotlin
    /** Worst first, which is the order the tally chips and the module list both use. */
    private val STATUS_ORDER = Status.entries.sortedByDescending { it.severity }
```

- [ ] **Step 4: Verify**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, 97 tests, 0 failures.

---

### Task 3: Let the store own its own path

`Main.savingsLine` recomputes the file path from a hardcoded `Path.of("data")`, so `--store out` prints `data/…`. This is the bug in the finding, not just the duplication.

**Files:**
- Modify: `engine/src/main/kotlin/kei/status/engine/ReportStore.kt:26-30`, `:64-82`
- Modify: `cli/src/main/kotlin/kei/status/cli/Main.kt:64-68`, `:141-151`
- Test: `engine/src/test/kotlin/kei/status/engine/ReportStoreTest.kt`

**Interfaces:**
- Consumes: `ReportStore.path(dir, module)`.
- Produces: `ReportStore.Saved(val report: RunReport, val written: Boolean, val changes: List<StatusChange>, val path: Path)` — `path` is the file the save targeted, whether or not it was rewritten.

- [ ] **Step 1: Write the failing test**

Add to `ReportStoreTest`:

```kotlin
@Test
fun `a save reports the file it targeted, not a guessed one`() {
    val dir = temporary.newFolder("somewhere-else").toPath()

    val saved = ReportStore.save(dir, report(":src:en:aurora"))

    assertEquals(dir.resolve("src-en-aurora.json"), saved.path)
}
```

(Match the file's existing `report(...)` helper; if none exists, build a `RunReport` the way the neighbouring tests do.)

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :engine:test --tests '*ReportStoreTest*'`
Expected: FAIL to compile — `Saved` has no `path`.

- [ ] **Step 3: Add `path` to `Saved` and populate it in `save`**

```kotlin
    data class Saved(
        val report: RunReport,
        val written: Boolean,
        val changes: List<StatusChange>,
        /** The store file this save targeted, rewritten or left alone. */
        val path: Path,
    )
```

In `save`, compute `val target = path(dir, report.module)` once, use it for the write, and return `Saved(next, written, changes, target)`.

- [ ] **Step 4: Use it in the CLI**

`Main.kt:64` becomes `val saved = options.store?.let { ReportStore.save(it, report) }` (unchanged), and `savingsLine` loses its `module` parameter:

```kotlin
private fun savingsLine(saved: ReportStore.Saved): String = when {
    !saved.written -> "  state unchanged; ${saved.path.fileName} left alone"
    saved.report.comparedTo == null -> "  new module recorded; wrote ${saved.path.fileName}"
    else -> buildString {
        append("  state changed (${saved.changes.size} transition(s)); wrote ${saved.path.fileName}")
        saved.changes.forEach { append("\n    ${it.render()}") }
    }
}
```

Call site becomes `saved?.let { println(savingsLine(it)) }`.

- [ ] **Step 5: Verify**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, 98 tests, 0 failures.

- [ ] **Step 6: Smoke-test the bug is gone**

Run: `./gradlew :cli:installDist && ./cli/build/install/cli/bin/cli --module :src:en:aurora --store /tmp/opencode/consolidate-store`
Expected: the savings line names a file inside `/tmp/opencode/consolidate-store`, never `data/`.

---

### Task 4: One declaration of the run defaults

**Files:**
- Modify: `engine/src/main/kotlin/kei/status/engine/HarnessRunner.kt:22-29`
- Modify: `cli/src/main/kotlin/kei/status/cli/Main.kt:301-304`, `:331-334`
- Modify: `ci/run-shard.sh:52-59`
- Test: `cli/src/test/kotlin/kei/status/cli/EntrySelectionTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `HarnessConfig.Companion` with `DEFAULT_TIMEOUT: JavaDuration`, `DEFAULT_MANGA_LIMIT: Int = 3`, `DEFAULT_PAGE_LIMIT: Int = 3`, `DEFAULT_PROBE_IMAGES: Boolean = true`. The data class's own defaults and `Options.parse` both read these.

- [ ] **Step 1: Write the failing test**

Add to `EntrySelectionTest`:

```kotlin
@Test
fun `an unset option takes the harness default`() {
    val options = Options.parse(arrayOf("--module", ":src:en:aurora"))
    val config = configFor(options, ModuleList.Entry(":src:en:aurora"), temporary.newFolder().toPath())

    assertEquals(HarnessConfig.DEFAULT_TIMEOUT, config.timeout)
    assertEquals(HarnessConfig.DEFAULT_MANGA_LIMIT, config.mangaLimit)
    assertEquals(HarnessConfig.DEFAULT_PAGE_LIMIT, config.pageLimit)
    assertEquals(HarnessConfig.DEFAULT_PROBE_IMAGES, config.probeImages)
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :cli:test --tests '*EntrySelectionTest*'`
Expected: FAIL to compile — no `HarnessConfig.DEFAULT_TIMEOUT`.

- [ ] **Step 3: Add the companion and point the data class at it**

```kotlin
data class HarnessConfig(
    val sourceRoot: Path,
    val module: String,
    val scratchRoot: Path,
    val timeout: JavaDuration = DEFAULT_TIMEOUT,
    val mangaLimit: Int = DEFAULT_MANGA_LIMIT,
    val pageLimit: Int = DEFAULT_PAGE_LIMIT,
    val probeImages: Boolean = DEFAULT_PROBE_IMAGES,
    ...
) {
    companion object {
        val DEFAULT_TIMEOUT: JavaDuration = JavaDuration.ofSeconds(45)
        const val DEFAULT_MANGA_LIMIT = 3
        const val DEFAULT_PAGE_LIMIT = 3
        const val DEFAULT_PROBE_IMAGES = true
    }
}
```

- [ ] **Step 4: Point the CLI at the same constants**

In `Options.parse`, replace the four literals with `HarnessConfig.DEFAULT_*`. In `USAGE`, interpolate the timeout so the help text cannot drift: `--timeout <seconds>   per-scenario timeout (default: ${HarnessConfig.DEFAULT_TIMEOUT.seconds})`.

- [ ] **Step 5: Drop the redundant flag from the shard runner**

Delete `--timeout 45` from `ci/run-shard.sh`; the CLI default now carries it. Keep `--source-root`, `--store`, `--module-list`.

- [ ] **Step 6: Verify**

Run: `./gradlew build && shellcheck --severity=warning ci/*.sh`
Expected: BUILD SUCCESSFUL, 99 tests, 0 failures; shellcheck silent.

---

### Task 5: One declaration of the page title

**Files:**
- Modify: `engine/src/main/kotlin/kei/status/engine/ReportHtml.kt:44-48`
- Modify: `cli/src/main/kotlin/kei/status/cli/Main.kt:206`, `:211-214`
- Modify: `ci/publish.sh:17-21`
- Test: `engine/src/test/kotlin/kei/status/engine/ReportHtmlTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `ReportHtml.DEFAULT_TITLE: String` (public `const val`).

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `the default title is the one the constant names`() {
    val html = ReportHtml.site(emptyList())
    assertTrue(html.contains("<title>${ReportHtml.DEFAULT_TITLE}</title>"))
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :engine:test --tests '*ReportHtmlTest*'`
Expected: FAIL to compile — no `DEFAULT_TITLE`.

- [ ] **Step 3: Move the constant and use it as the parameter default**

In `ReportHtml`: `const val DEFAULT_TITLE = "Keiyoushi extension status"`, and `title: String = DEFAULT_TITLE`.

- [ ] **Step 4: Delete `Main.DEFAULT_TITLE`** and read `ReportHtml.DEFAULT_TITLE` in `publish` and `titleOf`.

- [ ] **Step 5: Drop `--title` from `ci/publish.sh`** — it passed the value the CLI already defaults to.

- [ ] **Step 6: Verify**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, 100 tests, 0 failures.

---

### Task 6: Stop copying `--help` into the README

**Files:**
- Modify: `README.md:104-117`

**Interfaces:**
- Consumes: `Options.USAGE` (`cli/.../Main.kt:282`).
- Produces: nothing.

- [ ] **Step 1: Replace the option list** with one line naming the authority:

```markdown
Every option, with its default, is in `cli --help`; the run below is the common shape.
```

Keep the examples that follow (they carry information `--help` does not).

- [ ] **Step 2: Verify nothing else in the README still lists options**

Run: `grep -n '^--' README.md`
Expected: no match.

---

### Task 7: One implementation of discovery and sharding

Today `ModuleList.discover`/`shard` (Kotlin, tested) and `ci/discover.sh` (`find` + `awk`) implement the same two rules. CI uses the shell copy, so the tested copy is the one that never runs in CI.

Direction: the CLI keeps the rule; the shell keeps only the Actions glue (matrix JSON and `$GITHUB_OUTPUT`), which has no equivalent in Kotlin.

**Files:**
- Modify: `engine/src/main/kotlin/kei/status/engine/ModuleList.kt` (add a plan writer)
- Modify: `cli/src/main/kotlin/kei/status/cli/Main.kt` (add `--plan`, `--shards`)
- Modify: `ci/discover.sh` (delete discovery and shard arithmetic)
- Modify: `.github/workflows/status.yml:77-96` (build before discovering)
- Test: `engine/src/test/kotlin/kei/status/engine/ModuleListTest.kt`

**Interfaces:**
- Consumes: `ModuleList.read`, `ModuleList.discover`, `ModuleList.shard`.
- Produces: `ModuleList.plan(entries: List<Entry>, count: Int, outDir: Path): List<List<Entry>>` — writes `all.txt` and `shard-<n>.txt` for every `n` in `1..count` (empty files included) and returns the shards. An entry with args is written as `module arg1 arg2`.

- [ ] **Step 1: Write the failing test**

Add to `ModuleListTest`:

```kotlin
@Test
fun `a plan writes every shard and keeps entry flags`() {
    val out = temporary.newFolder("shards").toPath()
    val entries = listOf(
        ModuleList.Entry(":src:all:mangadex", listOf("--lang", "en")),
        ModuleList.Entry(":src:en:aurora"),
        ModuleList.Entry(":src:tr:hattorimanga"),
    )

    ModuleList.plan(entries, count = 2, outDir = out)

    assertEquals(
        listOf(":src:all:mangadex --lang en", ":src:en:aurora", ":src:tr:hattorimanga"),
        Files.readAllLines(out.resolve("all.txt")),
    )
    assertEquals(
        listOf(":src:all:mangadex --lang en", ":src:en:aurora"),
        Files.readAllLines(out.resolve("shard-1.txt")),
    )
    assertEquals(listOf(":src:tr:hattorimanga"), Files.readAllLines(out.resolve("shard-2.txt")))
    // A shard past the end still exists and is empty: the matrix loop tells "empty" from "missing".
    assertTrue(Files.exists(out.resolve("shard-3.txt")))
    assertTrue(Files.readAllLines(out.resolve("shard-3.txt")).isEmpty())
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :engine:test --tests '*ModuleListTest*'`
Expected: FAIL to compile — no `plan`.

- [ ] **Step 3: Implement `ModuleList.plan`**

Sort the rendered lines, write `all.txt`, then split with the existing `shard()` over the sorted entries and write one file per shard index in `1..count`, creating empty files for the tail.

- [ ] **Step 4: Add `--plan <dir>` and `--shards <n>` to the CLI**

`--plan` takes the directory and requires `--discover` or `--module-list`; it writes the plan, prints `discovered <total> module(s) in <shards> shard(s)`, and exits 0 without running anything. `--shards` defaults to 20. `--shard` keeps working for local runs.

- [ ] **Step 5: Rewrite `ci/discover.sh`**

Keep the usage contract (`discover.sh <extensions-source-dir> <out-dir> <shard-count> [list-file]`) and the `$GITHUB_OUTPUT` block. Replace the `find`/`awk` bodies with one call:

```bash
if [ -n "$list_file" ]; then
  ./cli/build/install/cli/bin/cli --plan "$out" --shards "$count" --module-list "$list_file"
else
  ./cli/build/install/cli/bin/cli --plan "$out" --shards "$count" --discover --source-root "$source_root"
fi
```

Keep the `total -eq 0` guard by reading `$out/all.txt`.

- [ ] **Step 6: Move the harness build ahead of discovery in `prepare`**

`prepare` must now build the harness before `discover.sh` can call it: order is checkout harness → checkout extensions-source → identify the revision → set up JDK → restore the dependency cache → build the harness → split the tree → upload the shard lists → upload the harness.

- [ ] **Step 7: Verify**

Run: `./gradlew build && ./gradlew :cli:installDist`
Expected: BUILD SUCCESSFUL.

Run: `./ci/discover.sh extensions-source /tmp/opencode/shards 20`
Expected: the same 1,444-ish module count as before, and `/tmp/opencode/shards/all.txt` identical to a run of the old script over the same tree. Compare with `git stash` if needed.

Run: `diff <(./ci/discover.sh extensions-source /tmp/opencode/new 20 >/dev/null && sort /tmp/opencode/new/all.txt) <(git stash -q && ./ci/discover.sh extensions-source /tmp/opencode/old 20 >/dev/null; git stash pop -q && sort /tmp/opencode/old/all.txt)`
Expected: no differences.

- [ ] **Step 8: Update the README**

`--discover`/`--shard` examples stay valid; add the `ci/discover.sh` route as what CI runs, so a reader can reproduce a shard locally.

---

### Task 8: One definition of the Gradle dependency cache

`prepare` and `sweep` both spell out the same paths, key and `restore-keys`. A key edited in one and not the other is a silent cache miss, not a failure.

**Files:**
- Create: `.github/actions/gradle-deps-cache/action.yml`
- Modify: `.github/workflows/status.yml:104-114`, `:154-176`

**Interfaces:**
- Consumes: nothing.
- Produces: a composite action with no inputs and no outputs, used as `uses: ./.github/actions/gradle-deps-cache`.

- [ ] **Step 1: Write the composite action**

```yaml
name: Gradle dependency cache
description: Restores the Maven and wrapper caches every job needs, keyed on the two version catalogs.

runs:
  using: composite
  steps:
    - uses: actions/cache@v6
      with:
        path: |
          ~/.gradle/caches/modules-2
          ~/.gradle/wrapper
        key: gradle-deps-${{ runner.os }}-${{ hashFiles('gradle/libs.versions.toml', 'extensions-source/gradle/libs.versions.toml', 'extensions-source/gradle/wrapper/gradle-wrapper.properties') }}
        restore-keys: |
          gradle-deps-${{ runner.os }}-
```

- [ ] **Step 2: Use it in `prepare`**, replacing the inline `actions/cache@v6` step.

- [ ] **Step 3: Use it in `sweep`**, replacing the inline `actions/cache/restore@v6` step.

`sweep` keeps its own per-shard build-cache step untouched.

- [ ] **Step 4: Update the cache comment**

The current comment explains why the shards use a restore-only action. With one shared action they use the saving one, and a hit means the save is skipped rather than performed; say that instead of the old rationale.

- [ ] **Step 5: Verify**

Run: `actionlint .github/workflows/status.yml .github/actions/gradle-deps-cache/action.yml`
Expected: no output. (Download the release binary to `/tmp/opencode/` if it is not installed.)

Do not dispatch the workflow.

---

## Final verification

- [ ] `./gradlew build` — BUILD SUCCESSFUL, 100 tests, 0 failures.
- [ ] `./gradlew :cli:installDist`
- [ ] `./cli/build/install/cli/bin/cli --help` — no `--discover` regression, `--plan` documented.
- [ ] `./ci/discover.sh extensions-source /tmp/opencode/shards 20` — 1,444 modules, same list as before the change.
- [ ] One real module end to end: `--module :src:en:aurora --store /tmp/opencode/store` — savings line names a path under `/tmp/opencode/store`.
- [ ] `git diff --stat` — only the files named above.
- [ ] `data/` untouched: `git status --porcelain data` is empty.
