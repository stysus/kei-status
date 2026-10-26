package kei.status.engine

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WriteAtomicallyTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `the target carries the content and no temp survives`() {
        val target = temporary.newFolder().toPath().resolve("out.json")

        writeAtomically(target, "body")

        assertEquals("body", Files.readString(target))
        assertFalse(
            "a finished write must not leave temp files in a committed directory",
            Files.list(target.parent).use { s -> s.anyMatch { it.fileName.toString().endsWith(".tmp") } },
        )
    }

    @Test
    fun `an existing target is replaced, not merged or refused`() {
        val target = temporary.newFolder().toPath().resolve("out.json")
        Files.writeString(target, "old")

        writeAtomically(target, "new")

        assertEquals("new", Files.readString(target))
    }

    @Test
    fun `a second write to the same target leaves no residue`() {
        val target = temporary.newFolder().toPath().resolve("out.json")

        repeat(3) { attempt -> writeAtomically(target, "version-$attempt") }

        assertEquals("version-2", Files.readString(target))
        assertTrue(Files.list(target.parent).use { s -> s.count() } == 1L)
    }
}
