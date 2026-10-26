package kei.status.engine

import java.nio.file.Path
import java.time.Duration as JavaDuration

/**
 * What a run needs to know about one module.
 *
 * This data class is the public contract between the CLI and the engine: the CLI reads its
 * defaults to populate `--help` and to parse flags, and the engine reads its fields to bound
 * a scenario.
 */
data class HarnessConfig(
    val sourceRoot: Path,
    val module: String,
    val scratchRoot: Path,
    val timeout: JavaDuration = DEFAULT_TIMEOUT,
    val mangaLimit: Int = DEFAULT_MANGA_LIMIT,
    val pageLimit: Int = DEFAULT_PAGE_LIMIT,
    val probeImages: Boolean = DEFAULT_PROBE_IMAGES,
    /** Restricts a multi-source module (e.g. MangaDex's ~60 languages) to these languages. */
    val langs: Set<String> = emptySet(),
) {

    companion object {
        /**
         * The run defaults, declared once.
         *
         * The option parser used to repeat these values and `ci/run-shard.sh` repeated the timeout
         * again, so a change to one of them left the others quietly reporting something else. The
         * parser now reads these, and the script passes nothing.
         */
        val DEFAULT_TIMEOUT: JavaDuration = JavaDuration.ofSeconds(45)
        const val DEFAULT_MANGA_LIMIT = 3
        const val DEFAULT_PAGE_LIMIT = 3
        const val DEFAULT_PROBE_IMAGES = true
    }
}

/** Set `KEI_STATUS_TRACE=1` to print stack traces for every classified failure. */
val TRACE: Boolean = System.getenv("KEI_STATUS_TRACE") != null
