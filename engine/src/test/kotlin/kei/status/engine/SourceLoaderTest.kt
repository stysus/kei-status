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

    @Test
    fun `a layout names the java classes dir beside the kotlin one`() {
        // ToonLivre ships ReaderVerificationActivity.java, which javac compiles into its own
        // directory; a layout that records only the Kotlin one leaves the class unloadable.
        val root = temporary.newFolder("extensions-source").toPath()

        val layout = layoutOf(root, runtimeJars = null, classesDirs = listOf(kotlinDir(root), javacDir(root)))

        assertEquals(listOf(kotlinDir(root), javacDir(root)), layout.classesDirs)
    }

    @Test
    fun `a layout written before the java dir was recorded still parses`() {
        val root = temporary.newFolder("extensions-source").toPath()

        val layout = layoutOf(root, runtimeJars = null)

        assertTrue(layout.classesDirs.isEmpty())
    }

    @Test
    fun `the java classes dir is on the class path without duplicating the kotlin one`() {
        val root = temporary.newFolder("extensions-source").toPath()

        val classpath = SourceLoader.classpathOf(
            root,
            layoutOf(root, null, classesDirs = listOf(kotlinDir(root), javacDir(root))),
        )

        assertEquals(1, classpath.count { it.endsWith("built_in_kotlinc/debug/compileDebugKotlin/classes") })
        assertTrue(classpath.any { it.endsWith("javac/debug/compileDebugJavaWithJavac/classes") })
    }

    @Test
    fun `a shared module's java classes are on the class path`() {
        // lib/secretstream is all Java files, and dilar links against it; the shared-module walk
        // has to look where javac puts its output too.
        val root = temporary.newFolder("extensions-source").toPath()
        val sharedJava = root.resolve("lib/secretstream")
            .resolve("build/intermediates/javac/debug/compileDebugJavaWithJavac/classes")
        Files.createDirectories(sharedJava)

        val classpath = SourceLoader.classpathOf(root, layoutOf(root, null))

        assertTrue("missing $sharedJava", classpath.contains(sharedJava))
    }

    /** Writes a real layout file, the way `dumpHarnessClasspath` does. */
    private fun layoutOf(root: Path, runtimeJars: List<String>?, classesDirs: List<String>? = null): ModuleLayout {
        val module = root.resolve("src/en/probe")
        val classes = module.resolve("build/classes")
        Files.createDirectories(classes)

        val json = buildString {
            appendLine("{")
            appendLine("  \"module\": \":src:en:probe\",")
            appendLine("  \"classesDir\": \"$classes\",")
            if (classesDirs != null) {
                appendLine("  \"classesDirs\": [")
                appendLine("    ${classesDirs.joinToString(",\n    ") { "\"$it\"" }}")
                appendLine("  ],")
            }
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

    private fun kotlinDir(root: Path) = root
        .resolve("src/en/probe/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")
        .toString()

    private fun javacDir(root: Path) = root
        .resolve("src/en/probe/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes")
        .toString()
}
