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

@Serializable
data class RunReport(
    val schemaVersion: Int = 1,
    val generatedAt: String,
    val module: String,
    val sourceRoot: String,
    val extensionCommit: String?,
    val sources: List<SourceReport>,
) {
    val status: Status
        get() = sources.maxByOrNull { it.status.severity }?.status ?: Status.SKIP
}
