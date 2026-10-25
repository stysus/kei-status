package kei.status.cli

import kei.status.engine.HarnessConfig
import java.nio.file.Path
import java.time.Duration

internal class Options(
    val module: String?,
    val moduleList: Path?,
    val discover: Boolean,
    val shard: Pair<Int, Int>?,
    val plan: Path?,
    val shards: Int?,
    val sourceRoot: Path,
    val output: Path?,
    val html: Path?,
    val store: Path?,
    val site: Path?,
    val out: Path?,
    val only: Path?,
    val prune: Boolean,
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
              --timeout <seconds>   per-scenario timeout (default: ${HarnessConfig.DEFAULT_TIMEOUT.seconds})
              --manga-limit <n>     manga sampled in the report (default: ${HarnessConfig.DEFAULT_MANGA_LIMIT})
              --page-limit <n>      pages probed (default: ${HarnessConfig.DEFAULT_PAGE_LIMIT})
              --no-images           skip downloading and decoding page images
              --lang <codes>        only run these languages, comma separated (multi-source modules)

            Plan options:
              --plan <dir>          write the shard plan to <dir> and exit, instead of running;
                                    needs --discover or --module-list, and --shards
              --shards <n>          shards to split the plan into

            Publish options:
              --site <dir>          aggregate every JSON report under <dir> into index.html
              --out <dir>           where to write index.html (default: the --site directory)
              --only <file>         publish only the modules listed in <file>, so a module that
                                    was dropped from the list does not linger on the page
              --prune               with --only, also delete the stored reports of modules the
                                    list no longer names, so the store does not keep them forever
              --title <text>        page title

            Other:
              --help                show this message
        """.trimIndent()

        fun parse(args: Array<String>): Options {
            var module: String? = null
            var moduleList: Path? = null
            var discover = false
            var shard: Pair<Int, Int>? = null
            var plan: Path? = null
            var shards: Int? = null
            var sourceRoot = Path.of("extensions-source")
            var output: Path? = null
            var html: Path? = null
            var store: Path? = null
            var site: Path? = null
            var out: Path? = null
            var only: Path? = null
            var prune = false
            var title: String? = null
            var timeout = HarnessConfig.DEFAULT_TIMEOUT
            var mangaLimit = HarnessConfig.DEFAULT_MANGA_LIMIT
            var pageLimit = HarnessConfig.DEFAULT_PAGE_LIMIT
            var probeImages = HarnessConfig.DEFAULT_PROBE_IMAGES
            var langs = emptySet<String>()
            var help = false

            var i = 0
            while (i < args.size) {
                when (val arg = args[i]) {
                    "--module" -> module = args.requireValue(++i, arg)
                    "--module-list" -> moduleList = Path.of(args.requireValue(++i, arg))
                    "--discover" -> discover = true
                    "--shard" -> shard = parseShard(args.requireValue(++i, arg))
                    "--plan" -> plan = Path.of(args.requireValue(++i, arg))
                    "--shards" -> shards = args.requireValue(++i, arg).toIntOrNull()
                        ?: throw IllegalArgumentException("--shards expects a number")
                    "--source-root" -> sourceRoot = Path.of(args.requireValue(++i, arg))
                    "--output" -> output = Path.of(args.requireValue(++i, arg))
                    "--html" -> html = Path.of(args.requireValue(++i, arg))
                    "--store" -> store = Path.of(args.requireValue(++i, arg))
                    "--site" -> site = Path.of(args.requireValue(++i, arg))
                    "--out" -> out = Path.of(args.requireValue(++i, arg))
                    "--only" -> only = Path.of(args.requireValue(++i, arg))
                    "--prune" -> prune = true
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

            if (plan != null) {
                if (!discover && moduleList == null) {
                    throw IllegalArgumentException("--plan needs --discover or --module-list")
                }
                if (site != null) {
                    throw IllegalArgumentException("--plan cannot be combined with --site")
                }
                if (shards == null) {
                    throw IllegalArgumentException("--plan requires --shards <n>")
                }
            } else if (shards != null) {
                throw IllegalArgumentException("--shards only applies to --plan")
            }
            if (shards != null && shards!! < 1) {
                throw IllegalArgumentException("--shards must be positive")
            }
            if (prune && only == null) {
                throw IllegalArgumentException("--prune requires --only: the list is what says which modules still exist")
            }

            return Options(
                module = module,
                moduleList = moduleList,
                discover = discover,
                shard = shard,
                plan = plan,
                shards = shards,
                sourceRoot = sourceRoot,
                output = output,
                html = html,
                store = store,
                site = site,
                out = out,
                only = only,
                prune = prune,
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
