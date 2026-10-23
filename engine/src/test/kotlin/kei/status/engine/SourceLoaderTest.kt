package kei.status.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

/**
 * An extension is packaged with its own dependencies inside the jar, so the app never has to know
 * about them; the harness loads a classes directory instead, and the runtime jars have to be named
 * beside it.
 *
 * `:lib:e4p` declares `implementation("org.kotlincrypto.hash:blake2")` and `:src:en:jnovel` links
 * against it, so both the layout file and the class path are checked for the jars here.
 */
class SourceLoaderTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `a layout file names the jars the module needs at run time`() {
        val root = temporary.newFolder("extensions-source").toPath()
        val jar = temporary.newFile("blake2.jar").toPath()

        val layout = layoutOf(root, runtimeJars = listOf(jar.toString()))

        assertEquals(listOf(jar.toString()), layout.runtimeJars)
    }

    @Test
    fun `a layout file written before the jars were recorded still parses`() {
        val root = temporary.newFolder("extensions-source").toPath()

        val layout = layoutOf(root, runtimeJars = null)

        assertTrue(layout.runtimeJars.isEmpty())
    }

    @Test
    fun `the runtime jars are on the class path, after the module's own classes`() {
        val root = temporary.newFolder("extensions-source").toPath()
        val jar = temporary.newFile("blake2.jar").toPath()

        val classpath = SourceLoader.classpathOf(root, layoutOf(root, listOf(jar.toString())))

        assertEquals(jar, classpath.last())
        assertTrue("classes dir must come first", classpath.first().endsWith("classes"))
    }

    @Test
    fun `a jar the layout names is reachable through the loader`() {
        val root = temporary.newFolder("extensions-source").toPath()
        val jar = temporary.root.toPath().resolve("dep.jar")
        writeJar(jar, "probe/marker.txt", "from the dependency")

        val classpath = SourceLoader.classpathOf(root, layoutOf(root, listOf(jar.toString())))

        URLClassLoader(classpath.map { it.toUri().toURL() }.toTypedArray()).use { loader ->
            val text = loader.getResourceAsStream("probe/marker.txt")?.reader()?.readText()
            assertEquals("from the dependency", text)
        }
    }

    /** Writes a real layout file, the way `dumpHarnessClasspath` does. */
    private fun layoutOf(root: Path, runtimeJars: List<String>?): ModuleLayout {
        val module = root.resolve("src/en/probe")
        val classes = module.resolve("build/classes")
        Files.createDirectories(classes)

        val json = buildString {
            appendLine("{")
            appendLine("  \"module\": \":src:en:probe\",")
            appendLine("  \"classesDir\": \"$classes\",")
            if (runtimeJars == null) {
                // The shape written before the jars were recorded: three keys, no more.
                appendLine("  \"moduleDir\": \"$module\"")
            } else {
                appendLine("  \"moduleDir\": \"$module\",")
                appendLine("  \"runtimeJars\": [")
                appendLine("    ${runtimeJars.joinToString(",\n    ") { "\"$it\"" }}")
                appendLine("  ]")
            }
            appendLine("}")
        }
        module.resolve("build/harness-classpath.json").toFile().writeText(json)

        return ModuleLayouts.read(root, ":src:en:probe")
    }

    private fun writeJar(jar: Path, entry: String, body: String) {
        JarOutputStream(Files.newOutputStream(jar)).use { out ->
            out.putNextEntry(ZipEntry(entry))
            out.write(body.toByteArray())
            out.closeEntry()
        }
    }
}
