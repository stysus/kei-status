package kei.status.engine

import kotlinx.serialization.Serializable

@Serializable
enum class Status {
    PASS,
    WARN,
    FAIL,
    SKIP,
    UNSUPPORTED,
    BLOCKED,
    ;

    /**
     * How bad this status is, for rolling several scenarios up into one verdict.
     *
     * `SKIP` ranks lowest: a source that simply has no "latest" endpoint is not in worse shape than
     * one that passed every check it supports.
     */
    val severity: Int
        get() = when (this) {
            FAIL -> 5
            BLOCKED -> 4
            UNSUPPORTED -> 3
            WARN -> 2
            PASS -> 1
            SKIP -> 0
        }
}

@Serializable
data class ScenarioResult(
    val name: String,
    val status: Status,
    val durationMs: Long,
    val message: String? = null,
    val notes: List<String> = emptyList(),
    val samples: List<String> = emptyList(),
)

@Serializable
data class SourceReport(
    val sourceId: Long,
    val name: String,
    val lang: String,
    val baseUrl: String,
    val status: Status,
    val scenarios: List<ScenarioResult>,
)

/**
 * One status transition between two runs.
 *
 * `from` and `to` are nullable so a source or scenario that appeared or disappeared is expressed
 * with the same shape as a normal change.
 */
@Serializable
data class StatusChange(
    val source: String,
    val lang: String,
    val scenario: String? = null,
    val from: Status? = null,
    val to: Status? = null,
)

@Serializable
data class RunReport(
    val schemaVersion: Int = 1,
    val generatedAt: String,
    val module: String,
    val sourceRoot: String,
    val extensionCommit: String?,
    val sources: List<SourceReport>,
    /**
     * `generatedAt` of the report this one replaced, or null on the first run.
     *
     * A stored report is only rewritten when its state changes, so this timestamp is when the
     * state last changed, not when the module was last checked.
     */
    val comparedTo: String? = null,
    val changes: List<StatusChange> = emptyList(),
) {
    val status: Status
        get() = sources.maxByOrNull { it.status.severity }?.status ?: Status.SKIP

    /**
     * The extension's name as the extension itself reports it, e.g. `AllManga` for
     * `:src:en:allanime`.
     *
     * The module path is a Gradle address, and the directory name is only a slug of the real name:
     * showing `allanime` above a source row that says `AllManga` reads like two different things.
     * The sources are the authority on the name; a module that failed to load has none, so its
     * path is humanised instead.
     */
    val displayName: String
        get() = sources.map { it.name }.distinct()
            .singleOrNull()
            ?: humanise(module)

    /**
     * The module's language bucket (`all`, `en`, `zh`, ...), empty when the path is not a source
     * module.
     *
     * A handful of extension names exist in more than one language, so this is what keeps two
     * identically named modules apart.
     */
    val lang: String
        get() = module.removePrefix(":").split(':')
            .takeIf { it.size >= 3 && it.first() == "src" }
            ?.get(1)
            ?: ""

    /** File name used in the store: `:src:en:aurora` becomes `src-en-aurora`. */
    val slug: String
        get() = module.removePrefix(":").replace(':', '-')

    private fun humanise(path: String): String =
        path.substringAfterLast(':')
            .replace('-', ' ')
            .replace('_', ' ')
            .replaceFirstChar { it.uppercaseChar() }
}
