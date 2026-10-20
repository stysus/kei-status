package kei.status.engine

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * Keeps one report per module on disk so a later run can compare against it.
 *
 * The store is meant to be committed: the file name is derived from the module path, the JSON is
 * pretty-printed so a diff is readable, and a report is only rewritten when its state changes. A
 * module that keeps passing therefore produces no diff at all, which is what makes the daily commit
 * worth reading instead of a wall of changing timestamps.
 */
object ReportStore {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /** Tolerates reports written by a newer harness than the one reading them. */
    private val decode = Json { ignoreUnknownKeys = true }

    /** Outcome of saving one report. */
    data class Saved(
        val report: RunReport,
        val written: Boolean,
        val changes: List<StatusChange>,
    )

    fun path(dir: Path, module: String): Path = dir.resolve(module.slugOf() + ".json")

    fun read(dir: Path, module: String): RunReport? {
        val file = path(dir, module)
        if (!Files.isRegularFile(file)) return null
        return runCatching { decode.decodeFromString<RunReport>(Files.readString(file)) }
            .getOrElse {
                // A hand-edited or truncated file should not stop a run; the next save replaces it.
                System.err.println("kei-status: ignoring unreadable store entry $file: ${it.message}")
                null
            }
    }

    fun readAll(dir: Path): List<RunReport> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                .sorted()
                .toList()
        }.mapNotNull { file ->
            runCatching { decode.decodeFromString<RunReport>(Files.readString(file)) }
                .onFailure { System.err.println("kei-status: ignoring $file: ${it.message}") }
                .getOrNull()
        }
    }

    /**
     * Records [report] and reports what changed against the entry it replaces.
     *
     * The file is left alone when only timestamps differ. That keeps the store stable and makes
     * `git diff` answer "what broke since yesterday" directly.
     */
    fun save(dir: Path, report: RunReport): Saved {
        Files.createDirectories(dir)
        val previous = read(dir, report.module)
        val changes = previous?.let { changes(it, report) } ?: emptyList()
        val next = report.copy(comparedTo = previous?.generatedAt, changes = changes)

        val written = previous == null || comparable(previous) != comparable(next)
        if (written) {
            // The checkout path is machine-specific and means nothing in a committed store; the
            // module path and the extension commit are what identify where the report came from.
            val stored = next.copy(sourceRoot = "")
            Files.writeString(path(dir, report.module), json.encodeToString(RunReport.serializer(), stored))
        }

        return Saved(next, written, changes)
    }

    /**
     * Fields that are expected to differ between two observations of the same state.
     *
     * `sourceRoot` is a local path, `generatedAt` is the clock, and `comparedTo`/`changes` describe
     * the transition rather than the state, so none of them mean the source got worse. Scenario
     * durations and the sampled manga titles change on every run by nature; a report is only worth
     * committing when a verdict, a reason, or the set of sources moved.
     *
     * `extensionCommit` is excluded on purpose. `extensions-source` takes commits daily, and a
     * stored report describes a *state*, established at `generatedAt` on `extensionCommit` - the
     * version where this verdict was first observed. A newer extension that reports the same
     * verdict leaves the entry alone; a newer extension that breaks something rewrites it, and the
     * diff then shows the new commit next to the transitions it caused.
     *
     * Sources and scenarios are sorted first so a site returning its language variants in a
     * different order does not look like a change.
     */
    private fun comparable(report: RunReport): RunReport = report.copy(
        generatedAt = "",
        sourceRoot = "",
        extensionCommit = null,
        comparedTo = null,
        changes = emptyList(),
        sources = report.sources
            .sortedWith(compareBy({ it.sourceId }, { it.lang }, { it.name }))
            .map { source ->
                source.copy(
                    scenarios = source.scenarios
                        .sortedBy { it.name }
                        .map { it.copy(durationMs = 0, samples = emptyList()) },
                )
            },
    )

    /**
     * Scenario-level transitions, plus source-level ones for sources that appeared or disappeared.
     *
     * A source's verdict is the worst of its scenarios, so recording both would say the same thing
     * twice: "pages FAIL -> PASS" already explains "source FAIL -> PASS".
     */
    fun changes(previous: RunReport, current: RunReport): List<StatusChange> {
        val before = previous.sources.associateBy { it.key() }
        val after = current.sources.associateBy { it.key() }
        val out = mutableListOf<StatusChange>()

        after.forEach { (key, now) ->
            val was = before[key]
            if (was == null) {
                out += StatusChange(now.name, now.lang, to = now.status)
                return@forEach
            }

            val beforeScenarios = was.scenarios.associateBy { it.name }
            val afterScenarios = now.scenarios.associateBy { it.name }

            afterScenarios.forEach { (name, scenario) ->
                val previousScenario = beforeScenarios[name]
                when {
                    previousScenario == null ->
                        out += StatusChange(now.name, now.lang, name, to = scenario.status)

                    previousScenario.status != scenario.status ->
                        out += StatusChange(now.name, now.lang, name, previousScenario.status, scenario.status)
                }
            }

            beforeScenarios.forEach { (name, scenario) ->
                if (name !in afterScenarios) {
                    out += StatusChange(now.name, now.lang, name, from = scenario.status)
                }
            }

            // A verdict that moved without any scenario moving would mean the roll-up is wrong;
            // record it rather than hiding it.
            if (was.status != now.status && was.scenarios.map { it.name } == now.scenarios.map { it.name } &&
                was.scenarios.map { it.status } == now.scenarios.map { it.status }
            ) {
                out += StatusChange(now.name, now.lang, from = was.status, to = now.status)
            }
        }

        before.forEach { (key, was) ->
            if (key !in after) out += StatusChange(was.name, was.lang, from = was.status)
        }

        return out
    }

    /**
     * Identity of a source across runs.
     *
     * The id is not enough on its own: a module that fails to load reports id 0, and a module that
     * swaps implementations could reuse one.
     */
    private fun SourceReport.key(): String = "$sourceId|$lang|$name"

    private fun String.slugOf(): String = removePrefix(":").replace(':', '-')
}
