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
    DOWN,
    THROTTLED,
    ;

    /**
     * How bad this status is, for rolling several scenarios up into one verdict.
     *
     * `SKIP` ranks lowest: a source that simply has no "latest" endpoint is not in worse shape than
     * one that passed every check it supports.
     *
     * `THROTTLED` ranks below `BLOCKED` and above `UNSUPPORTED`: the site asked us to slow down, so
     * the source was not checked - that is not the extension failing, but it is not a pass either.
     * It outranks `PASS` on purpose. The local checks (`metadata`, `filters`) still pass when a
     * sweep is throttled, and a roll-up that let them win would report an unchecked source as
     * healthy.
     */
    val severity: Int
        get() = when (this) {
            FAIL -> 7
            DOWN -> 6
            BLOCKED -> 5
            THROTTLED -> 4
            UNSUPPORTED -> 3
            WARN -> 2
            PASS -> 1
            SKIP -> 0
        }

    /**
     * One sentence for the report page, for a reader who has not seen a sweep before.
     *
     * It lives beside [severity] because both answer the same question - what does this verdict
     * mean? - and the page renders it rather than keeping a second copy in the renderer.
     *
     * Keep in step with the reason sets that assign these statuses: [ExceptionDiagnosis.diagnose]
     * for thrown exceptions, plus the inline SKIP/WARN classifications in [HarnessRunner] (429
     * already seen, self-hosted hosts, empty listings, page notes) and [Checks]. Cloudflare
     * origin errors (520-530) are classified as [DOWN] by [ExceptionDiagnosis].
     */
    val meaning: String
        get() = when (this) {
            PASS -> "ran and met the contract"
            WARN -> "ran, but something is off (blank thumbnail, empty list, malformed URL)"
            FAIL -> "exception, timeout, or broken contract"
            SKIP -> "nothing was checked: the entry point is unsupported, the listing was empty, or the host answered 429 earlier"
            UNSUPPORTED -> "needs a platform class the harness does not provide, or the harness's browser session failed"
            BLOCKED -> "anti-bot challenge; the source is not at fault"
            DOWN -> "the site's origin server is not responding (Cloudflare 5xx); the source is not at fault"
            THROTTLED -> "the site is rate-limiting the sweep (HTTP 429); the source was not checked"
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
    val throttledHosts: List<String> = emptyList(),
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

    private fun humanise(path: String): String =
        path.substringAfterLast(':')
            .replace('-', ' ')
            .replace('_', ' ')
            .replaceFirstChar { it.uppercaseChar() }
}
