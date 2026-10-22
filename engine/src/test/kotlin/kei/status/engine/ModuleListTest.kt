package kei.status.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/**
 * The module list is what drives both the run and the published page, so its format is parsed in
 * one place instead of being re-invented by each reader.
 */
class ModuleListTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun file(vararg lines: String) = temporary.newFile().toPath().also {
        Files.write(it, lines.toList())
    }

    @Test
    fun `a line is a module plus optional flags`() {
        val entries = ModuleList.read(
            file(
                "# modules checked by CI",
                "",
                "   ",
                "  # indented comment",
                ":src:all:mangadex --lang en",
                ":src:en:aurora --no-images # slow site",
                ":src:tr:hattorimanga",
            ),
        )

        // Comments and blank lines carry no entry; flags and trailing comments are not modules.
        assertEquals(
            listOf(
                ModuleList.Entry(":src:all:mangadex", listOf("--lang", "en")),
                ModuleList.Entry(":src:en:aurora", listOf("--no-images")),
                ModuleList.Entry(":src:tr:hattorimanga"),
            ),
            entries,
        )
    }

    @Test
    fun `modules returns the set used to filter the page`() {
        val modules = ModuleList.modules(file(":src:en:aurora --lang en", ":src:tr:hattorimanga"))

        assertEquals(setOf(":src:en:aurora", ":src:tr:hattorimanga"), modules)
    }

    @Test
    fun `a missing list is empty rather than an error`() {
        assertTrue(ModuleList.read(temporary.root.toPath().resolve("nope.txt")).isEmpty())
    }

    @Test
    fun `only directories with a build script are modules`() {
        // Gradle registers a module from its build script, so the tree must agree: a scratch
        // directory and a hidden tool directory are not extensions.
        val root = temporary.newFolder("extensions-source").toPath()
        listOf("en/aurora", "en/asurascans", "tr/hattorimanga", "all/mangadex", "en/scratch").forEach {
            Files.createDirectories(root.resolve("src/$it"))
        }
        Files.createDirectories(root.resolve("src/.gradle/x"))
        listOf("en/aurora", "en/asurascans", "tr/hattorimanga", "all/mangadex").forEach {
            Files.writeString(root.resolve("src/$it/build.gradle.kts"), "")
        }

        val modules = ModuleList.discover(root).map { it.module }

        assertEquals(
            listOf(":src:all:mangadex", ":src:en:asurascans", ":src:en:aurora", ":src:tr:hattorimanga"),
            modules,
        )
    }

    @Test
    fun `a missing source tree discovers nothing`() {
        assertTrue(ModuleList.discover(temporary.root.toPath().resolve("nope")).isEmpty())
    }

    @Test
    fun `a plan writes every shard and keeps entry flags`() {
        // The CI shard lists and the local `--shard` used to be computed by two different pieces of
        // code: this one, and a `find` + `awk` in `ci/discover.sh`. A plan file is the shared output
        // both now agree on, so this pins its exact shape.
        val out = temporary.newFolder("shards").toPath()
        val entries = listOf(
            ModuleList.Entry(":src:all:mangadex", listOf("--lang", "en")),
            ModuleList.Entry(":src:en:aurora"),
            ModuleList.Entry(":src:tr:hattorimanga"),
            ModuleList.Entry(":src:zh:dynasty"),
        )

        ModuleList.plan(entries, count = 3, outDir = out)

        assertEquals(
            listOf(
                ":src:all:mangadex --lang en",
                ":src:en:aurora",
                ":src:tr:hattorimanga",
                ":src:zh:dynasty",
            ),
            Files.readAllLines(out.resolve("all.txt")),
        )
        assertEquals(
            listOf(":src:all:mangadex --lang en", ":src:en:aurora"),
            Files.readAllLines(out.resolve("shard-1.txt")),
        )
        assertEquals(
            listOf(":src:tr:hattorimanga", ":src:zh:dynasty"),
            Files.readAllLines(out.resolve("shard-2.txt")),
        )
        // A shard past the end still exists and is empty: the matrix loop tells "empty" from
        // "missing", and a missing file would look like a shard that failed to report in.
        assertTrue(Files.exists(out.resolve("shard-3.txt")))
        assertTrue(Files.readAllLines(out.resolve("shard-3.txt")).isEmpty())
    }

    @Test
    fun `shards split the modules evenly without losing or repeating one`() {
        val entries = (1..10).map { ModuleList.Entry(":src:en:m$it") }

        val shards = (1..3).map { ModuleList.shard(entries, it, 3) }

        // Together they are the whole list, in order, each module in exactly one shard.
        assertEquals(entries, shards.flatten())
        assertEquals(listOf(4, 4, 2), shards.map { it.size })
        assertEquals(":src:en:m5", shards[1].first().module)

        // More shards than modules is a normal way to scale down a run.
        assertTrue(ModuleList.shard(entries.take(2), 5, 10).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an out of range shard is rejected`() {
        ModuleList.shard(listOf(ModuleList.Entry(":src:en:a")), 4, 3)
    }
}
