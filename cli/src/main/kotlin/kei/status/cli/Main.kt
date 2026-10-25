package kei.status.cli

import kei.status.engine.HarnessConfig
import kei.status.engine.ModuleList
import kei.status.engine.HarnessRunner
import kei.status.engine.ReportHtml
import kei.status.engine.ReportStore
import kei.status.engine.RunReport
import kei.status.engine.Status
import kei.status.engine.StatusChange
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
            val report = try {
                HarnessRunner(config).run()
            } catch (e: Throwable) {
                System.err.println("kei-status: ${entry.module}: ${e.message}")
                if (System.getenv("KEI_STATUS_TRACE") != null) e.printStackTrace()
                return@forEachIndexed
            }

            val storeToUse = storeToWrite(options, config)
            val saved = storeToUse?.let { ReportStore.save(it, report) }
            if (options.store != null && storeToUse == null) {
                println("  narrowed run (--lang); the store keeps the full sweep's entry for ${entry.module}")
            }
            results += report to saved

            if (entries.size == 1) printTable(report) else printSummary(position + 1, entries.size, report, saved)
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

    // A single module can still write a standalone page; a sweep publishes through --site.
    if (results.size == 1) {
        val effective = results.single().second?.report ?: results.single().first
        options.output?.let { path ->
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, json.encodeToString(effective))
            println("\nreport written to $path")
        }
        options.html?.let { path ->
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, ReportHtml.site(listOf(effective), titleOf(options.title, effective.module)))
            println("page written to $path")
        }
    }

    val changed = results.count { it.second?.written == true }
    val transitions = results.sumOf { it.second?.changes?.size ?: 0 }
    println("\n${results.size} module(s) checked, $changed changed, $transitions transition(s)")

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
 * Scenarios each module ran on its last sweep, which is what a shard split balances.
 *
 * The store is the only place that knows a module is a hundred-source aggregate rather than a
 * single extension, and it is checked out wherever a plan is written. A missing entry - a new
 * module, or one whose state was pruned - is left out rather than guessed at; [ModuleList.split]
 * falls back to the typical module for those.
 */
internal fun moduleCosts(options: Options): Map<String, Int> {
    val dir = options.store ?: DEFAULT_STORE
    return ReportStore.readAll(dir).associate { report ->
        report.module to report.sources.sumOf { it.scenarios.size }
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
    val count = options.shards ?: return 2

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

    val used = ModuleList.plan(entries, count, out, moduleCosts(options)).count { it.isNotEmpty() }
    println("discovered ${entries.size} module(s) in $used shard(s)")
    return 0
}

internal fun configFor(options: Options, entry: ModuleList.Entry, scratch: Path): HarnessConfig {
    // Entry flags are parsed with the same rules as the command line, so `--lang en` on one line of
    // a list means the same as `--lang en` on the command line.
    val overrides = try {
        Options.parse(options.runArgs.toTypedArray() + entry.args.toTypedArray() + arrayOf("--module", entry.module))
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("${entry.module}: ${e.message}")
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
private fun printSummary(position: Int, total: Int, report: RunReport, saved: ReportStore.Saved?) {
    val sources = report.sources
    val label = report.module
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
    println("[${position.toString().padStart(4)}/${total}] ${report.status.name.padEnd(11)} $label  $detail$change")
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
    val trace = System.getenv("KEI_STATUS_TRACE") != null
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        System.err.println(
            "kei-status: background failure on ${thread.name}: " +
                "${error::class.java.name}: ${error.message}",
        )
        if (trace) error.printStackTrace()
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
