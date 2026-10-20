package kei.status.cli

import kei.status.engine.HarnessConfig
import kei.status.engine.HarnessRunner
import kei.status.engine.RunReport
import kei.status.engine.Status
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

    val scratch = Files.createTempDirectory("kei-status-")
    val report = try {
        HarnessRunner(
            HarnessConfig(
                sourceRoot = options.sourceRoot,
                module = options.module,
                scratchRoot = scratch,
                timeout = options.timeout,
                mangaLimit = options.mangaLimit,
                pageLimit = options.pageLimit,
                probeImages = options.probeImages,
                langs = options.langs,
            ),
        ).run()
    } catch (e: Throwable) {
        System.err.println("kei-status: ${e.message}")
        e.printStackTrace()
        exitProcess(3)
    } finally {
        // The browser outlives any single scenario; stop it here so the run leaves no process or
        // temporary profile behind even if the JVM is kept alive by a stray extension thread.
        runCatching { kei.status.webkit.Chrome.stop() }
    }

    printTable(report)
    options.output?.let { path ->
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, json.encodeToString(report))
        println("\nreport written to $path")
    }

    exitProcess(if (report.status == Status.FAIL) 1 else 0)
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

private class Options(
    val module: String,
    val sourceRoot: Path,
    val output: Path?,
    val timeout: Duration,
    val mangaLimit: Int,
    val pageLimit: Int,
    val probeImages: Boolean,
    val langs: Set<String>,
    val help: Boolean,
) {
    companion object {
        val USAGE = """
            Usage: kei-status --module <:src:lang:name> [options]

            Options:
              --module <path>       Gradle module path, e.g. :src:en:aurora (required)
              --source-root <dir>   extensions-source checkout (default: ./extensions-source)
              --output <file>       write a JSON report
              --timeout <seconds>   per-scenario timeout (default: 45)
              --manga-limit <n>     manga sampled in the report (default: 3)
              --page-limit <n>      pages probed (default: 3)
              --no-images           skip downloading and decoding page images
              --lang <codes>        only run these languages, comma separated (multi-source modules)
              --help                show this message
        """.trimIndent()

        fun parse(args: Array<String>): Options {
            var module: String? = null
            var sourceRoot = Path.of("extensions-source")
            var output: Path? = null
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
                    "--source-root" -> sourceRoot = Path.of(args.requireValue(++i, arg))
                    "--output" -> output = Path.of(args.requireValue(++i, arg))
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

            if (!help && module == null) throw IllegalArgumentException("--module is required")

            return Options(
                module = module.orEmpty(),
                sourceRoot = sourceRoot,
                output = output,
                timeout = timeout,
                mangaLimit = mangaLimit,
                pageLimit = pageLimit,
                probeImages = probeImages,
                langs = langs,
                help = help,
            )
        }

        private fun Array<String>.requireValue(index: Int, flag: String): String =
            getOrNull(index) ?: throw IllegalArgumentException("$flag requires a value")
    }
}
