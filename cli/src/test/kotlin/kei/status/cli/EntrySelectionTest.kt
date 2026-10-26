package kei.status.cli

import kei.status.engine.HarnessConfig
import kei.status.engine.ModuleList
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `resolveEntries` decides what a run actually checks. Getting this wrong is quiet and expensive:
 * a broken `--module-list` once made every run sweep the whole tree instead of the requested
 * subset, which looked like a working run until the summary line was read.
 */
class EntrySelectionTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `module list is used when given`() {
        val root = sourceRoot(":src:en:aurora", ":src:en:erisscans")
        val list = file(":src:en:erisscans")

        val entries = resolveEntries(
            Options.parse(arrayOf("--module-list", list.toString(), "--source-root", root.toString())),
        )

        assertEquals(listOf(ModuleList.Entry(":src:en:erisscans")), entries)
    }

    @Test
    fun `discovery is used when no list is given`() {
        val root = sourceRoot(":src:en:aurora", ":src:en:erisscans")

        val entries = resolveEntries(Options.parse(arrayOf("--discover", "--source-root", root.toString())))

        assertEquals(
            listOf(ModuleList.Entry(":src:en:aurora"), ModuleList.Entry(":src:en:erisscans")),
            entries,
        )
    }

    @Test
    fun `a single module runs alone`() {
        val entries = resolveEntries(Options.parse(arrayOf("--module", ":src:all:mangadex", "--lang", "en")))

        assertEquals(listOf(ModuleList.Entry(":src:all:mangadex")), entries)
    }

    @Test
    fun `a narrowed run does not write the store`() {
        // The store records the state of a full run: one entry per module, every source. A --lang
        // run reports a subset, and saving it would rewrite the entry with the other sources
        // missing - the kei-status example `run :src:all:mangadex --lang en --store data` used to
        // do exactly that to the nightly sweep's 61-source entry.
        val options = Options.parse(arrayOf("--module", ":src:all:mangadex", "--lang", "en", "--store", "data"))
        val config = configFor(options, ModuleList.Entry(":src:all:mangadex"), Path.of("scratch"))

        assertNull(storeToWrite(options, config))
    }

    @Test
    fun `a run without narrowing writes the store`() {
        val options = Options.parse(arrayOf("--module", ":src:all:mangadex", "--store", "data"))
        val config = configFor(options, ModuleList.Entry(":src:all:mangadex"), Path.of("scratch"))

        assertEquals(Path.of("data"), storeToWrite(options, config))
    }

    @Test
    fun `narrowing on a module-list line reaches the same guard`() {
        // The command line may be bare while the entry carries the flags, so the effective config
        // is what decides - not the options the user typed.
        val options = Options.parse(arrayOf("--module", ":src:all:mangadex", "--store", "data"))
        val config = configFor(options, ModuleList.Entry(":src:all:mangadex", listOf("--lang", "en")), Path.of("scratch"))

        assertNull(storeToWrite(options, config))
    }

    @Test
    fun `a module list keeps per-line flags`() {
        val list = file(":src:all:mangadex --lang en", ":src:en:aurora")

        val entries = resolveEntries(Options.parse(arrayOf("--module-list", list.toString())))

        assertEquals(
            listOf(
                ModuleList.Entry(":src:all:mangadex", listOf("--lang", "en")),
                ModuleList.Entry(":src:en:aurora"),
            ),
            entries,
        )
    }

    @Test
    fun `a shard narrows the selected list`() {
        val list = file(*(1..10).map { ":src:en:m$it" }.toTypedArray())
        fun shard(which: String) =
            resolveEntries(Options.parse(arrayOf("--module-list", list.toString(), "--shard", which)))

        val first = shard("1/2")
        val second = shard("2/2")

        // The two halves are the whole list with nothing counted twice. Which module lands in which
        // half is the split's business (it balances on cost), not this test's: five and five summing
        // to all ten means no module was dropped or repeated.
        assertEquals(5, first.size)
        assertEquals(5, second.size)
        assertEquals(
            (1..10).map { ":src:en:m$it" }.toSet(),
            (first + second).map { it.module }.toSet(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a plan without a shard count is rejected`() {
        // `ci/discover.sh` always has a count; a default here would be a third place for it to live.
        Options.parse(arrayOf("--discover", "--plan", "shards"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a shard count without a plan is rejected`() {
        Options.parse(arrayOf("--discover", "--shards", "4"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `--module and --discover are mutually exclusive`() {
        Options.parse(arrayOf("--module", ":src:en:aurora", "--discover"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `--module and --module-list are mutually exclusive`() {
        Options.parse(arrayOf("--module", ":src:en:aurora", "--module-list", file(":src:en:b").toString()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `--module-list and --discover are mutually exclusive`() {
        Options.parse(arrayOf("--module-list", file(":src:en:a").toString(), "--discover"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `--timeout rejects non-numeric input`() {
        Options.parse(arrayOf("--module", ":src:en:a", "--timeout", "abc"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `--timeout rejects negative values`() {
        Options.parse(arrayOf("--module", ":src:en:a", "--timeout", "-5"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `--timeout rejects zero`() {
        Options.parse(arrayOf("--module", ":src:en:a", "--timeout", "0"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `--manga-limit rejects zero`() {
        Options.parse(arrayOf("--module", ":src:en:a", "--manga-limit", "0"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `--page-limit rejects negative values`() {
        Options.parse(arrayOf("--module", ":src:en:a", "--page-limit", "-3"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `--html without --module is rejected`() {
        Options.parse(arrayOf("--discover", "--html", "page.html"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `--output without --module is rejected`() {
        Options.parse(arrayOf("--module-list", file(":src:en:a").toString(), "--output", "report.json"))
    }

    @Test
    fun `an unset option takes the harness default`() {
        // The defaults used to be written twice, in `HarnessConfig` and again in the option parser,
        // so a change to one left the other reporting something else.
        val options = Options.parse(arrayOf("--module", ":src:en:aurora"))
        val config = configFor(options, ModuleList.Entry(":src:en:aurora"), temporary.newFolder().toPath())

        assertEquals(HarnessConfig.DEFAULT_TIMEOUT, config.timeout)
        assertEquals(HarnessConfig.DEFAULT_MANGA_LIMIT, config.mangaLimit)
        assertEquals(HarnessConfig.DEFAULT_PAGE_LIMIT, config.pageLimit)
        assertEquals(HarnessConfig.DEFAULT_PROBE_IMAGES, config.probeImages)
    }

    @Test
    fun `command line run flags reach the harness config`() {
        val options = Options.parse(
            arrayOf("--module", ":src:all:mangadex", "--lang", "en", "--timeout", "9", "--no-images"),
        )

        val config = configFor(options, ModuleList.Entry(":src:all:mangadex"), temporary.newFolder().toPath())

        assertEquals(setOf("en"), config.langs)
        assertEquals(9L, config.timeout.seconds)
        assertEquals(false, config.probeImages)
    }

    @Test
    fun `a module list entry overrides the command line`() {
        val list = file(":src:all:mangadex --lang ja")
        val options = Options.parse(arrayOf("--module-list", list.toString(), "--lang", "en"))

        val config = configFor(options, resolveEntries(options).single(), temporary.newFolder().toPath())

        assertEquals(setOf("ja"), config.langs)
    }

    private fun file(vararg lines: String): Path = temporary.newFile().toPath().also {
        Files.write(it, lines.toList())
    }

    /** A minimal `extensions-source` layout: only directories with a build script are modules. */
    private fun sourceRoot(vararg modules: String): Path {
        val root = temporary.newFolder().toPath()
        modules.forEach { module ->
            val path = root.resolve(module.removePrefix(":").replace(':', '/'))
            path.createDirectories()
            path.resolve("build.gradle.kts").writeText("")
        }
        return root
    }
}
