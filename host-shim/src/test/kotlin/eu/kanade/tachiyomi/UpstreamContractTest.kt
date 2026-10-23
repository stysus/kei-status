package eu.kanade.tachiyomi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Guards the link-time contract between host-shim and the extensions-lib the sources are built
 * against.
 *
 * Every extension and `core/` compile with `compileOnly(extensions-lib)`, so each
 * `eu.kanade.tachiyomi.*` call site in their bytecode carries a descriptor taken from that jar. When
 * the shim's descriptor differs the call fails at link time with `NoSuchMethodError` - which is how
 * `HttpException(int)` went missing when the shim grew a `retryAfterSeconds` parameter and dropped
 * the upstream constructor along with it.
 *
 * The contract is a checked-in snapshot rather than the artifact itself, so this runs without an
 * Android toolchain. The snapshot records the pinned version, so a snapshot generated against a
 * different extensions-lib is caught here. That pin lives in `extensions-source/`, which is not part
 * of this repository - when the directory is absent (as in `ci.yml`, where the sweep has not checked
 * it out yet) the pin test is skipped and `tools/upstream-contract.py --check` in the sweep compares
 * the whole snapshot against the artifact instead. Regenerate with
 * `python3 tools/upstream-contract.py`.
 */
class UpstreamContractTest {

    @Test
    fun `the snapshot matches the pinned extensions-lib version`() {
        val toml = repoRoot().resolve("extensions-source/gradle/libs.versions.toml")
        // `extensions-source/` is not part of this repository - the sweep checks it out - so a
        // plain `./gradlew build` has no pin to compare against. The sweep runs
        // `tools/upstream-contract.py --check`, which compares the whole snapshot (pin included)
        // against the artifact, and is the only place the real pin is visible.
        assumeTrue("$toml is absent; the sweep verifies the pin against the artifact", toml.isFile)

        assertEquals(
            "upstream-contract.txt was generated for a different extensions-lib.\n" +
                "Regenerate it: python3 tools/upstream-contract.py",
            pinnedExtensionsLib(toml),
            snapshotPin(),
        )
    }

    @Test
    fun `every member the pinned library declares exists with the same descriptor`() {
        val missing = mutableListOf<String>()

        for (line in snapshotMembers()) {
            val parts = line.split(' ')
            val kind = parts[0]
            val owner = parts[1]
            val name = parts[2]
            val descriptor = parts[3]

            if (owner in NOT_IMPLEMENTED) continue

            val type = declaredClass(owner)
                ?: run {
                    missing += "$owner is not on the shim class path"
                    continue
                }

            val found = when (kind) {
                "method" -> hasMethod(type, name, descriptor)
                "field" -> hasField(type, name, descriptor)
                else -> error("unknown member kind '$kind' in the contract snapshot")
            }
            if (!found) missing += "$owner.$name$descriptor"
        }

        assertTrue(
            "host-shim does not satisfy the pinned extensions-lib contract. Extension bytecode that " +
                "calls these fails at link time with NoSuchMethodError/NoSuchFieldError:\n  " +
                missing.joinToString("\n  "),
            missing.isEmpty(),
        )
    }

    @Test
    fun `every class keeps the supertypes extension code relies on`() {
        // Descriptors cover member linkage; this covers the other half of a link: `catch (e:
        // HttpException)` and `as CatalogueSource` depend on the class still being what it was.
        // Additive interfaces are fine, so the check is assignability, not equality.
        val lost = mutableListOf<String>()

        for (line in snapshotSupertypes()) {
            val (prefix, owner, expected) = line.split(' ')
            check(prefix == "extends") { "unexpected line '$line'" }
            if (owner in NOT_IMPLEMENTED) continue

            val type = declaredClass(owner) ?: continue
            val expectedType = declaredClass(expected)
                ?: run {
                    lost += "$expected is not on the test class path, so $owner cannot be checked"
                    continue
                }
            if (!expectedType.isAssignableFrom(type)) lost += "$owner is no longer a $expected"
        }

        assertTrue(
            "host-shim changed the type hierarchy extensions compile against:\n  " +
                lost.joinToString("\n  "),
            lost.isEmpty(),
        )
    }

    @Test
    fun `every exclusion is still a real gap`() {
        // An exclusion is a promise that the class is deliberately unimplemented. If the shim grows
        // the class later the promise is stale and would quietly stop guarding its members.
        val stale = NOT_IMPLEMENTED.keys.filter { declaredClass(it) != null }

        assertTrue(
            "these classes are excluded from the contract but now exist on the shim class path; " +
                "remove them from NOT_IMPLEMENTED so their members are checked: $stale",
            stale.isEmpty(),
        )
    }

    /** Classes the harness has no host implementation for; a source that links against them fails. */
    private val NOT_IMPLEMENTED = mapOf(
        // Running extension JavaScript needs an embedded engine the harness does not ship.
        "eu.kanade.tachiyomi.network.JavaScriptEngine" to "no JavaScript engine in the harness",
    )

    private fun declaredClass(name: String): Class<*>? =
        try {
            Class.forName(name, false, javaClass.classLoader)
        } catch (e: ClassNotFoundException) {
            null
        }

    /**
     * Method resolution as the JVM does it: the class, then its superclasses, then its interfaces -
     * except a constructor, which is never inherited.
     */
    private fun hasMethod(type: Class<*>, name: String, descriptor: String): Boolean {
        if (name == "<init>") {
            return type.declaredConstructors.any { descriptorOf(it) == descriptor }
        }
        return findMethod(type, name, descriptor) != null
    }

    private fun findMethod(
        type: Class<*>,
        name: String,
        descriptor: String,
        seen: MutableSet<Class<*>> = mutableSetOf(),
    ): Method? {
        if (!seen.add(type)) return null
        type.declaredMethods.firstOrNull { it.name == name && descriptorOf(it) == descriptor }
            ?.let { return it }
        type.superclass?.let { findMethod(it, name, descriptor, seen)?.let { found -> return found } }
        for (iface in type.interfaces) {
            findMethod(iface, name, descriptor, seen)?.let { return it }
        }
        return null
    }

    private fun hasField(type: Class<*>, name: String, descriptor: String): Boolean =
        findField(type, name, descriptor) != null

    private fun findField(
        type: Class<*>,
        name: String,
        descriptor: String,
        seen: MutableSet<Class<*>> = mutableSetOf(),
    ): Field? {
        if (!seen.add(type)) return null
        type.declaredFields.firstOrNull { it.name == name && descriptorOf(it.type) == descriptor }
            ?.let { return it }
        type.superclass?.let { findField(it, name, descriptor, seen)?.let { found -> return found } }
        for (iface in type.interfaces) {
            findField(iface, name, descriptor, seen)?.let { return it }
        }
        return null
    }

    private fun descriptorOf(constructor: java.lang.reflect.Constructor<*>): String =
        constructor.parameterTypes.joinToString("", "(", ")") { descriptorOf(it) } + "V"

    private fun descriptorOf(method: Method): String =
        method.parameterTypes.joinToString("", "(", ")") { descriptorOf(it) } +
            descriptorOf(method.returnType)

    private fun descriptorOf(type: Class<*>): String = when {
        type == Void.TYPE -> "V"
        type == java.lang.Boolean.TYPE -> "Z"
        type == java.lang.Byte.TYPE -> "B"
        type == Character.TYPE -> "C"
        type == java.lang.Short.TYPE -> "S"
        type == Integer.TYPE -> "I"
        type == java.lang.Long.TYPE -> "J"
        type == java.lang.Float.TYPE -> "F"
        type == java.lang.Double.TYPE -> "D"
        type.isArray -> "[" + descriptorOf(type.componentType)
        else -> "L" + type.name.replace('.', '/') + ";"
    }

    private fun snapshotLines(): List<String> =
        (javaClass.classLoader.getResourceAsStream(SNAPSHOT)
            ?: error("$SNAPSHOT is missing from the test resources; run python3 tools/upstream-contract.py"))
            .bufferedReader()
            .readLines()
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun snapshotPin(): String =
        snapshotLines().firstOrNull { it.startsWith("pin=") }?.removePrefix("pin=")
            ?: error("$SNAPSHOT has no pin= line; run python3 tools/upstream-contract.py")

    private fun snapshotMembers(): List<String> =
        snapshotLines().filter { it.startsWith("method ") || it.startsWith("field ") }

    private fun snapshotSupertypes(): List<String> =
        snapshotLines().filter { it.startsWith("extends ") }

    /** The version the extension build compiles against, read from its own version catalogue. */
    private fun pinnedExtensionsLib(toml: File): String {
        val pattern = Regex(
            """com\.github\.keiyoushi:extensions-lib"\s*,\s*version\s*=\s*"([^"]+)"""",
        )
        return pattern.find(toml.readText())?.groupValues?.get(1)
            ?: error("no com.github.keiyoushi:extensions-lib version in $toml")
    }

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile
        }
        error("could not find the repository root from ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val SNAPSHOT = "upstream-contract.txt"
    }
}
