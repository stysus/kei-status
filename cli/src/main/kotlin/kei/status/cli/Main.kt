package kei.status.cli

import kei.status.engine.HarnessConfig
import kei.status.engine.ModuleList
import kei.status.engine.HarnessRunner
import kei.status.engine.TRACE
import kotlinx.coroutines.CancellationException
import kei.status.engine.ReportHtml
import kei.status.engine.ReportStore
import kei.status.engine.RunReport
import kei.status.engine.Status
import kei.status.engine.StatusChange
import kei.status.engine.writeAtomically
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    installBackgroundFailureHandler()

    val options = try {
        Options.parse(args)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        System.err.println(Options.USAGE)
        exitProcess(2)
    }

    if (options.help) {
        println(Options.USAGE)
        return
    }

    // A plan is the shard list a sweep runs, written out and nothing else. It is the one
    // implementation of "what is a module" and "how the list is split", which `ci/discover.sh`
    // calls instead of repeating the rules in `find` and `awk`.
    val planDir = options.plan
    if (planDir != null) {
        exitProcess(writePlan(options, planDir))
    }

    // Aggregation reads reports that other runs already wrote, so it needs no browser or source.
    if (options.site != null) {
        exitProcess(publish(options.site, options.out ?: options.site, options.title, options.only, options.prune))
    }

    val entries = try {
        resolveEntries(options)
    } catch (e: IllegalArgumentException) {
        System.err.println("kei-status: ${e.message}")
        exitProcess(2)
    }
    if (entries.isEmpty()) {
        System.err.println("kei-status: nothing to run")
        exitProcess(2)
    }

    // One JVM for the whole run: Chromium and the class loader are expensive to build, and a full
    // sweep is thousands of modules.
    val scratch = Files.createTempDirectory("kei-status-")
    val results = mutableListOf<Pair<RunReport, ReportStore.Saved?>>()
    val moduleTimes = mutableMapOf<String, Long>()
    val startedAt = System.nanoTime()
    try {
        entries.forEachIndexed { position, entry ->
            // Entry flags are parsed per module, so one malformed line is reported and skipped the
            // same way a failing module is, rather than aborting the modules behind it.
            val config = try {
                configFor(options, entry, scratch)
            } catch (e: IllegalArgumentException) {
                System.err.println("kei-status: ${e.message}")
                return@forEachIndexed
            }
            val moduleStart = System.nanoTime()
            val report = try {
                HarnessRunner(config).run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                HarnessRunner.crashReport(config, e)
            }

            val storeToUse = storeToWrite(options, config)
            val saved = storeToUse?.let { store ->
                try {
                    ReportStore.save(store, report)
                } catch (e: Exception) {
                    System.err.println("kei-status: could not save state for ${entry.module} to $store: ${e.message}")
                    if (TRACE) e.printStackTrace()
                    null
                }
            }
            moduleTimes[report.module] = (System.nanoTime() - moduleStart) / 1_000_000
            if (options.store != null && storeToUse == null) {
                println("  narrowed run (--lang); the store keeps the full sweep's entry for ${entry.module}")
            }
            results += report to saved

            if (entries.size == 1) printTable(report) else printSummary(
                position + 1, entries.size, report, saved,
                moduleTimes[report.module] ?: 0,
            )
            saved?.let { println(savingsLine(it)) }
        }
    } finally {
        // The browser outlives any single scenario; stop it here so the run leaves no process or
        // temporary profile behind even if the JVM is kept alive by a stray extension thread.
        runCatching { kei.status.webkit.Chrome.stop() }
        // The scratch root holds the extension data (prefs, HTTP cache) this run mounted under
        // Application.attachRoot. With the browser down nothing else writes to it, so one
        // recursive delete retires it; runs otherwise shed one of these into /tmp per invocation.
        runCatching { scratch.toFile().deleteRecursively() }
    }

    // Write a costs file so the next run's shard split uses measured wall-clock time instead of
    // the store's stale durationMs.  A module that stays PASS for a month still gets a fresh
    // timing every night.  The measurements merge into (rather than replace) the file: a
    // single-module or narrowed run would otherwise erase every other module's timing until the
    // next full sweep re-measured it.  The file sits next to the store reports but uses a name
    // that readAll's "*.json" filter does not match, so it is never parsed as a RunReport.
    if (moduleTimes.isNotEmpty()) {
        options.store?.let { store ->
            val merged = mergedCosts(store, moduleTimes)
            val body = merged.entries
                .sortedBy { it.key }
                .joinToString(",\n  ", prefix = "{\n  ", postfix = "\n}") { (module, ms) ->
                    "\"${module}\": $ms"
                }
            runCatching { writeAtomically(store.resolve("costs"), body) }
                .onFailure { System.err.println("kei-status: could not write costs file to $store: ${it.message}") }
        }
    }

    // A single module can still write a standalone page; a sweep publishes through --site.
    if (results.size == 1) {
        val effective = results.single().second?.report ?: results.single().first
        options.output?.let { path ->
            path.parent?.let { Files.createDirectories(it) }
            writeAtomically(path, json.encodeToString(effective))
            println("\nreport written to $path")
        }
        options.html?.let { path ->
            path.parent?.let { Files.createDirectories(it) }
            writeAtomically(path, ReportHtml.site(listOf(effective), titleOf(options.title, effective.module)))
            println("page written to $path")
        }
    }

    val changed = results.count { it.second?.written == true }
    val transitions = results.sumOf { it.second?.changes?.size ?: 0 }
    // The first line is load-bearing: `ci/run-shard.sh` greps `^0 module(s) checked` to reject a
    // run that checked nothing, so its shape must not drift.
    println("\n${summaryLine(results.size, changed, transitions)}")

    val dropped = entries.size - results.size
    if (dropped > 0) {
        println("$dropped module(s) dropped by config errors before producing a verdict")
    }

    val verdicts = results.groupingBy { it.first.status }.eachCount()
    if (verdicts.isNotEmpty()) {
        val breakdown = Status.entries
            .sortedByDescending { it.severity }
            .filter { it in verdicts }
            .joinToString(", ") { "${verdicts[it]} ${it.name}" }
        println("verdicts: $breakdown")
    }
    val refused = results.flatMap { it.first.throttledHosts }.distinct()
    if (refused.isNotEmpty()) {
        println("throttled by ${refused.size} host(s): ${refused.joinToString(", ")}")
    }
    println("checked in ${formatDuration(System.nanoTime() - startedAt)}")

    moduleTimes.entries.sortedByDescending { it.value }.take(5)
        .takeIf { it.size > 1 }
        ?.let { slowest ->
            println("slowest: " + slowest.joinToString(", ") { (module, ms) ->
                "$module ${formatDuration(ms * 1_000_000)}"
            })
        }

    exitProcess(exitCode(results))
}

/**
 * The process exit code a run reports.
 *
 * A run that checked nothing did not succeed: every module was dropped by an error before it could
 * produce a verdict. The exit code alone cannot say so - 1 is already the sweep's normal finding -
 * so `ci/run-shard.sh` also rejects the "0 module(s) checked" summary line this run prints, and
 * that is what fails the CI job instead of recording its marker. With results in hand the rule is
 * the worst verdict seen.
 */
internal fun exitCode(results: List<Pair<RunReport, ReportStore.Saved?>>): Int =
    when (results.map { it.first }.maxByOrNull { it.status.severity }?.status) {
        null -> 1
        Status.FAIL -> 1
        else -> 0
    }

/**
 * The summary line `ci/run-shard.sh` greps for.  Extracted so [SummaryFormatTest] can verify the
 * shape without running a whole sweep.
 */
internal fun summaryLine(checked: Int, changed: Int, transitions: Int): String =
    "$checked module(s) checked, $changed changed, $transitions transition(s)"

/**
 * The modules a run selects, before any shard narrowing.
 *
 * `--module-list` is a file of modules, either a hand-written subset or the output of
 * `--discover`; `--module` is the single-module form kept for debugging one extension.
 */
internal fun selectedEntries(options: Options): List<ModuleList.Entry> = when {
    options.discover -> ModuleList.discover(options.sourceRoot)
    options.moduleList != null -> ModuleList.read(options.moduleList)
    options.module != null -> listOf(ModuleList.Entry(options.module))
    else -> throw IllegalArgumentException("--module, --module-list or --discover is required")
}

/** The modules to run, narrowed to [Options.shard] when one was asked for. */
internal fun resolveEntries(options: Options): List<ModuleList.Entry> {
    val all = selectedEntries(options)
    val (index, count) = options.shard ?: return all
    return ModuleList.shard(all, index, count, moduleCosts(options))
}

/**
 * What each module cost on its last sweep, in wall-clock milliseconds.
 *
 * The primary source is the `costs` file written at the end of every run: it records actual
 * wall-clock time (compilation + load + scenarios), so the data is at most one night old even for
 * a module whose status has not changed in months.  Without it, [ReportStore.save] only rewrites
 * a report on a status change, which freezes the stored `durationMs` at whatever it was on the
 * night the status last moved.
 *
 * When the costs file does not exist (first run, or an old checkout), the store's `durationMs`
 * is used as a fallback; the split's median-based fallback handles the rest.
 */
internal fun moduleCosts(options: Options): Map<String, Int> {
    val dir = options.store ?: DEFAULT_STORE
    val costsFile = dir.resolve("costs")
    if (Files.isRegularFile(costsFile)) {
        return runCatching {
            val obj = Json.parseToJsonElement(Files.readString(costsFile)) as JsonObject
            obj.mapValues { it.value.jsonPrimitive.long.toInt() }
        }.getOrElse {
            System.err.println("kei-status: ignoring unreadable costs file $costsFile: ${it.message}")
            moduleCostsFromStore(dir)
        }
    }
    return moduleCostsFromStore(dir)
}

/**
 * This run's measured wall-clock times merged into the store's costs file.
 *
 * The merge, not a replace, is what lets a single-module run write costs at all: the nightly
 * sweep is the only run that measures every module, and a debugging session in between must not
 * erase the other 1,400 timings the plan depends on. This run's entries win per module — the
 * same rule the publish job unions the per-shard views by. An unreadable file is dropped with a
 * warning, the same recovery apply.sh uses.
 */
internal fun mergedCosts(dir: Path, measured: Map<String, Long>): Map<String, Long> {
    val costsFile = dir.resolve("costs")
    val existing = if (Files.isRegularFile(costsFile)) {
        runCatching {
            Json.parseToJsonElement(Files.readString(costsFile))
                .let { it as JsonObject }
                .mapValues { it.value.jsonPrimitive.long }
        }.getOrElse {
            System.err.println("kei-status: ignoring unreadable costs file $costsFile: ${it.message}")
            emptyMap()
        }
    } else {
        emptyMap()
    }
    return existing + measured
}

private fun moduleCostsFromStore(dir: Path): Map<String, Int> =
    ReportStore.readAll(dir).associate { report ->
        report.module to report.sources.sumOf { source ->
            source.scenarios.sumOf { it.durationMs.toInt() }
        }
    }

/** Where a run reads and writes state when `--store` is not given. */
private val DEFAULT_STORE: Path = Path.of("data")

/**
 * The store directory this run may write, or null when it must not.
 *
 * The store records the state of a *full* run: one entry per module, every source the module
 * declares. A run narrowed with `--lang` reports a subset, and saving that would rewrite the entry
 * with the other sources missing - the nightly sweep's 61-source MangaDex entry reduced to one by
 * the local debugging session that followed it. The narrowing can come from the command line or
 * from flags on a `--module-list` line, so the effective config decides, not the options alone.
 */
internal fun storeToWrite(options: Options, config: HarnessConfig): Path? =
    options.store?.takeIf { config.langs.isEmpty() }

/**
 * Writes the shard plan and reports how much it covers.
 *
 * The shard count is required rather than defaulted: the only caller is `ci/discover.sh`, which
 * always has one, and a default here would be a third place for that number to live.
 */
private fun writePlan(options: Options, out: Path): Int {
    // Options.parse refuses --plan without --shards, so the count is always here by the time the
    // command dispatches.
    val count = options.shards!!

    val entries = try {
        selectedEntries(options)
    } catch (e: IllegalArgumentException) {
        System.err.println("kei-status: ${e.message}")
        return 2
    }

    if (entries.isEmpty()) {
        val where = if (options.discover) options.sourceRoot else options.moduleList
        System.err.println("kei-status: no modules found in $where")
        return 2
    }

    val costs = moduleCosts(options)
    val bins = ModuleList.plan(entries, count, out, costs)
    val used = bins.count { it.isNotEmpty() }
    println("discovered ${entries.size} module(s) in $used shard(s)")
    if (used > 1) {
        // The costs mix wall-clock (from the costs file) and scenario time (from the store
        // fallback), so these are relative balance indicators, not absolute wall-clock estimates.
        val fallback = ModuleList.fallbackCost(costs)
        val loads = bins.filter { it.isNotEmpty() }
            .map { bin -> bin.sumOf { (costs[it.module] ?: fallback).toLong() } }
        println("projected load: ${loads.joinToString(" / ") { formatDuration(it * 1_000_000) }}")
    }
    return 0
}

internal fun configFor(options: Options, entry: ModuleList.Entry, scratch: Path): HarnessConfig {
    // When the entry carries no flags of its own, the command-line options already hold every
    // value and re-parsing would reach the same result.
    val overrides = if (entry.args.isEmpty()) {
        options
    } else {
        try {
            Options.parse(options.runArgs.toTypedArray() + entry.args.toTypedArray() + arrayOf("--module", entry.module))
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("${entry.module}: ${e.message}")
        }
    }

    return HarnessConfig(
        sourceRoot = options.sourceRoot,
        module = entry.module,
        scratchRoot = scratch,
        timeout = overrides.timeout,
        mangaLimit = overrides.mangaLimit,
        pageLimit = overrides.pageLimit,
        probeImages = overrides.probeImages,
        langs = overrides.langs,
    )
}

private fun savingsLine(saved: ReportStore.Saved): String = when {
    !saved.written -> "  state unchanged; ${saved.path} left alone"
    saved.report.comparedTo == null -> "  new module recorded; wrote ${saved.path}"
    else -> buildString {
        append("  state changed (${saved.changes.size} transition(s)); wrote ${saved.path}")
        saved.changes.forEach { append("\n    ${it.render()}") }
    }
}

/** One line per module, because a sweep of a thousand modules cannot print a table each. */
private fun printSummary(
    position: Int,
    total: Int,
    report: RunReport,
    saved: ReportStore.Saved?,
    wallClockMs: Long,
) {
    val sources = report.sources
    val label = report.module
    val duration = formatDuration(wallClockMs * 1_000_000)
    val detail = when {
        sources.isEmpty() -> "no sources"
        sources.size == 1 -> sources.single().scenarios.joinToString(" ") { it.status.name.first().toString() }
        else -> "${sources.size} sources"
    }
    val change = when {
        saved == null -> ""
        !saved.written -> "  (unchanged)"
        else -> "  (${saved.changes.size} changed)"
    }
    println(
        "[${position.toString().padStart(4)}/${total}] ${report.status.name.padEnd(11)} " +
            duration.padStart(6) + "  $label  $detail$change",
    )
}

private fun StatusChange.render(): String {
    val what = if (scenario == null) "source" else scenario
    val from = from?.name ?: "new"
    val to = to?.name ?: "gone"
    return "$source [$lang] $what: $from -> $to"
}

private val json = Json {
    prettyPrint = true
    encodeDefaults = true
}

/**
 * Extensions start fire-and-forget work (e.g. `KeiSource`'s filter prefetch on `GlobalScope`).
 * When that work fails, the stack trace lands on stderr and drowns the report. Keep a one-line
 * note instead; `KEI_STATUS_TRACE=1` restores the full trace for debugging.
 */
private fun installBackgroundFailureHandler() {
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        System.err.println(
            "kei-status: background failure on ${thread.name}: " +
                "${error::class.java.name}: ${error.message}",
        )
        if (TRACE) error.printStackTrace()
    }
}

private fun printTable(report: RunReport) {
    println("kei-status — ${report.module} (extension ${report.extensionCommit ?: "unknown"})")
    println()

    report.sources.forEach { source ->
        println("${source.name} [${source.lang}] ${source.baseUrl}")
        source.scenarios.forEach { scenario ->
            val notes = buildList {
                scenario.message?.let { add(it) }
                addAll(scenario.notes)
            }
            val suffix = if (notes.isEmpty()) "" else "  ${notes.joinToString("; ")}"
            println("  ${scenario.status.name.padEnd(11)} ${scenario.name.padEnd(14)} ${scenario.durationMs}ms$suffix")
        }
        println("  -> ${source.status}")
        println()
    }
}

private fun formatDuration(nanos: Long): String {
    val seconds = nanos / 1_000_000_000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m${(seconds % 60).toString().padStart(2, '0')}s"
        else -> "${seconds / 3600}h${((seconds % 3600) / 60).toString().padStart(2, '0')}m"
    }
}
