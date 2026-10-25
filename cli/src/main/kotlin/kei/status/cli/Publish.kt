package kei.status.cli

import kei.status.engine.ModuleList
import kei.status.engine.ReportHtml
import kei.status.engine.ReportStore
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads every JSON report under [siteDir] and writes a single page to [outDir].
 *
 * A report that cannot be parsed is skipped with a note rather than aborting the publish: one
 * malformed module should not take the whole status page down. With [prune] its file goes too: an
 * entry is named after its module, so the name alone says it is not one that still exists.
 */
internal fun publish(siteDir: Path, outDir: Path, title: String?, only: Path?, prune: Boolean): Int {
    if (!Files.isDirectory(siteDir)) {
        System.err.println("kei-status: --site expects a directory of JSON reports: $siteDir")
        return 2
    }

    val allowed = only?.let { ModuleList.modules(it) }
    if (only != null && allowed.isNullOrEmpty()) {
        System.err.println("kei-status: --only lists no modules: $only")
        return 2
    }
    if (prune && allowed == null) {
        System.err.println("kei-status: --prune needs --only: without a list of what exists, everything would go")
        return 2
    }

    val stored = ReportStore.readAll(siteDir)
    val reports = if (allowed == null) stored else stored.filter { it.module in allowed }
    val dropped = stored.size - reports.size
    if (dropped > 0) {
        System.err.println("kei-status: not publishing $dropped stored module(s) absent from $only")
    }

    Files.createDirectories(outDir)
    val index = outDir.resolve("index.html")
    Files.writeString(index, ReportHtml.site(reports, title ?: ReportHtml.DEFAULT_TITLE))
    println("published ${reports.size} report(s) to $index")

    // After the page, not before: a render that fails must leave the store as it found it.
    if (prune && allowed != null) {
        val removed = ReportStore.prune(siteDir, allowed)
        if (removed.isNotEmpty()) {
            System.err.println("kei-status: removed ${removed.size} store entr(ies) for module(s) no longer listed")
        }
    }
    return 0
}

internal fun titleOf(title: String?, module: String): String =
    title ?: "${ReportHtml.DEFAULT_TITLE} - $module"
