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
import java.time.Duration
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

    // Aggregation reads reports that other runs already wrote, so it needs no browser or source.
    if (options.site != null) {
        exitProcess(publish(options.site, options.out ?: options.site, options.title, options.only))
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
            val report = try {
                HarnessRunner(configFor(options, entry, scratch)).run()
            } catch (e: Throwable) {
                System.err.println("kei-status: ${entry.module}: ${e.message}")
                if (System.getenv("KEI_STATUS_TRACE") != null) e.printStackTrace()
                return@forEachIndexed
            }

            val saved = options.store?.let { ReportStore.save(it, report) }
            results += report to saved

            if (entries.size == 1) printTable(report) else printSummary(position + 1, entries.size, report, saved)
            saved?.let { println(savingsLine(it, report.module)) }
        }
    } finally {
        // The browser outlives any single scenario; stop it here so the run leaves no process or
        // temporary profile behind even if the JVM is kept alive by a stray extension thread.
        runCatching { kei.status.webkit.Chrome.stop() }
    }

    val worst = results.map { it.first }.maxByOrNull { it.status.severity }

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

    exitProcess(if (worst?.status == Status.FAIL) 1 else 0)
}

/**
 * Turns the options into the modules to run.
 *
 * `--module-list` is a file of modules, either a hand-written subset or the output of
 * `--discover`; `--module` is the single-module form kept for debugging one extension.
 */
internal fun resolveEntries(options: Options): List<ModuleList.Entry> {
    val all = when {
        options.discover -> ModuleList.discover(options.sourceRoot)
        options.moduleList != null -> ModuleList.read(options.moduleList)
        options.module != null -> listOf(ModuleList.Entry(options.module))
        else -> throw IllegalArgumentException("--module, --module-list or --discover is required")
    }

    if (options.shard == null) return all

    val (index, count) = options.shard
    return ModuleList.shard(all, index, count)
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

private fun savingsLine(saved: ReportStore.Saved, module: String): String {
    val target = ReportStore.path(Path.of("data"), module)
    return when {
        !saved.written -> "  state unchanged; ${target.fileName} left alone"
        saved.report.comparedTo == null -> "  new module recorded; wrote ${target.fileName}"
        else -> buildString {
            append("  state changed (${saved.changes.size} transition(s)); wrote ${target.fileName}")
            saved.changes.forEach { append("\n    ${it.render()}") }
        }
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

/**
 * Reads every JSON report under [siteDir] and writes a single page to [outDir].
 *
 * A report that cannot be parsed is skipped with a note rather than aborting the publish: one
 * malformed module should not take the whole status page down.
 */
private fun publish(siteDir: Path, outDir: Path, title: String?, only: Path?): Int {
    if (!Files.isDirectory(siteDir)) {
        System.err.println("kei-status: --site expects a directory of JSON reports: $siteDir")
        return 2
    }

    val allowed = only?.let { ModuleList.modules(it) }
    if (only != null && allowed.isNullOrEmpty()) {
        System.err.println("kei-status: --only lists no modules: $only")
        return 2
    }

    // A module removed from the list keeps its stored file, but the page must not present it as
    // checked by the run that is publishing.
    val stored = ReportStore.readAll(siteDir)
    val reports = if (allowed == null) stored else stored.filter { it.module in allowed }
    val dropped = stored.size - reports.size
    if (dropped > 0) {
        System.err.println("kei-status: not publishing $dropped stored module(s) absent from $only")
    }

    Files.createDirectories(outDir)
    val index = outDir.resolve("index.html")
    Files.writeString(index, ReportHtml.site(reports, title ?: DEFAULT_TITLE))
    println("published ${reports.size} report(s) to $index")
    return 0
}

private fun titleOf(title: String?, module: String): String =
    title ?: "$DEFAULT_TITLE - $module"

private const val DEFAULT_TITLE = "Keiyoushi extension status"

private val json = Json {
    prettyPrint = true
    encodeDefaults = true
}

/** Tolerates fields added by a newer harness than the one reading the file. */
private val decode = Json { ignoreUnknownKeys = true }

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

internal class Options(
    val module: String?,
    val moduleList: Path?,
    val discover: Boolean,
    val shard: Pair<Int, Int>?,
    val sourceRoot: Path,
    val output: Path?,
    val html: Path?,
    val store: Path?,
    val site: Path?,
    val out: Path?,
    val only: Path?,
    val title: String?,
    val timeout: Duration,
    val mangaLimit: Int,
    val pageLimit: Int,
    val probeImages: Boolean,
    val langs: Set<String>,
    val help: Boolean,
    /** Module-run flags as given on the command line, so a list entry can override them. */
    val runArgs: List<String> = emptyList(),
) {
    companion object {
        val USAGE = """
            Usage: kei-status --module <:src:lang:name> [options]
                   kei-status --module-list <file> [--shard <i>/<n>] [options]
                   kei-status --discover [--shard <i>/<n>] [options]
                   kei-status --site <reports-dir> [--out <dir>] [--title <text>]

            Runs one extension module and reports per-scenario health, or aggregates the JSON
            reports of several runs into one static page.

            Run options:
              --module <path>       one Gradle module path, e.g. :src:en:aurora
              --module-list <file>  run every module listed in <file>
              --discover            run every module in the source tree
              --shard <i>/<n>       run only shard i of n, so several jobs can split the work
              --source-root <dir>   extensions-source checkout (default: ./extensions-source)
              --output <file>       write a JSON report
              --store <dir>         keep the report in a directory of per-module files, so the
                                    next run can report what changed
              --html <file>         write a standalone HTML page for this run
              --timeout <seconds>   per-scenario timeout (default: 45)
              --manga-limit <n>     manga sampled in the report (default: 3)
              --page-limit <n>      pages probed (default: 3)
              --no-images           skip downloading and decoding page images
              --lang <codes>        only run these languages, comma separated (multi-source modules)

            Publish options:
              --site <dir>          aggregate every JSON report under <dir> into index.html
              --out <dir>           where to write index.html (default: the --site directory)
              --only <file>         publish only the modules listed in <file>, so a module that
                                    was dropped from the list does not linger on the page
              --title <text>        page title

            Other:
              --help                show this message
        """.trimIndent()

        fun parse(args: Array<String>): Options {
            var module: String? = null
            var moduleList: Path? = null
            var discover = false
            var shard: Pair<Int, Int>? = null
            var sourceRoot = Path.of("extensions-source")
            var output: Path? = null
            var html: Path? = null
            var store: Path? = null
            var site: Path? = null
            var out: Path? = null
            var only: Path? = null
            var title: String? = null
            var timeout = Duration.ofSeconds(45)
            var mangaLimit = 3
            var pageLimit = 3
            var probeImages = true
            var langs = emptySet<String>()
            var help = false

            var i = 0
            while (i < args.size) {
                when (val arg = args[i]) {
                    "--module" -> module = args.requireValue(++i, arg)
                    "--module-list" -> moduleList = Path.of(args.requireValue(++i, arg))
                    "--discover" -> discover = true
                    "--shard" -> shard = parseShard(args.requireValue(++i, arg))
                    "--source-root" -> sourceRoot = Path.of(args.requireValue(++i, arg))
                    "--output" -> output = Path.of(args.requireValue(++i, arg))
                    "--html" -> html = Path.of(args.requireValue(++i, arg))
                    "--store" -> store = Path.of(args.requireValue(++i, arg))
                    "--site" -> site = Path.of(args.requireValue(++i, arg))
                    "--out" -> out = Path.of(args.requireValue(++i, arg))
                    "--only" -> only = Path.of(args.requireValue(++i, arg))
                    "--title" -> title = args.requireValue(++i, arg)
                    "--timeout" -> timeout = Duration.ofSeconds(args.requireValue(++i, arg).toLong())
                    "--manga-limit" -> mangaLimit = args.requireValue(++i, arg).toInt()
                    "--page-limit" -> pageLimit = args.requireValue(++i, arg).toInt()
                    "--no-images" -> probeImages = false
                    "--lang" -> langs = args.requireValue(++i, arg)
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet()
                    "--help", "-h" -> help = true
                    else -> throw IllegalArgumentException("Unknown option: $arg")
                }
                i++
            }

            if (!help && module == null && moduleList == null && !discover && site == null) {
                throw IllegalArgumentException("--module, --module-list, --discover or --site is required")
            }

            return Options(
                module = module,
                moduleList = moduleList,
                discover = discover,
                shard = shard,
                sourceRoot = sourceRoot,
                output = output,
                html = html,
                store = store,
                site = site,
                out = out,
                only = only,
                title = title,
                timeout = timeout,
                mangaLimit = mangaLimit,
                pageLimit = pageLimit,
                probeImages = probeImages,
                langs = langs,
                help = help,
                runArgs = moduleRunFlags(args),
            )
        }

        /**
         * The flags that only affect a single module run, in the order given.
         *
         * A list entry is parsed on its own so it can carry per-module flags; the command line's
         * values are prepended so they act as defaults the entry can override.
         */
        private fun moduleRunFlags(args: Array<String>): List<String> {
            val valued = setOf("--timeout", "--manga-limit", "--page-limit", "--lang")
            return buildList {
                var i = 0
                while (i < args.size) {
                    val arg = args[i]
                    when {
                        arg in valued -> {
                            add(arg)
                            args.getOrNull(i + 1)?.let { add(it) }
                            i++
                        }
                        arg == "--no-images" -> add(arg)
                    }
                    i++
                }
            }
        }

        private fun Array<String>.requireValue(index: Int, flag: String): String =
            getOrNull(index) ?: throw IllegalArgumentException("$flag requires a value")

        /** `--shard 3/20` means "shard 3 of 20". */
        private fun parseShard(value: String): Pair<Int, Int> {
            val (index, count) = value.split('/', limit = 2)
                .map { it.trim().toIntOrNull() ?: throw IllegalArgumentException("--shard expects <i>/<n>, got $value") }
                .let { if (it.size == 2) it else throw IllegalArgumentException("--shard expects <i>/<n>, got $value") }

            if (count <= 0) throw IllegalArgumentException("--shard count must be positive")
            if (index !in 1..count) throw IllegalArgumentException("--shard index must be between 1 and $count")
            return index to count
        }
    }
}
