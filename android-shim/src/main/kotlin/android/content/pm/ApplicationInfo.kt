package android.content.pm

/**
 * Stand-in for android.content.pm.ApplicationInfo.
 *
 * TCB Scans reads `dataDir` from its `init` block to migrate a very old preference file, so the
 * whole extension fails to load without this class - the field is reached through `getfield`, which
 * only links once the type exists.
 */
class ApplicationInfo {

    @JvmField
    var dataDir: String = ""

    @JvmField
    var packageName: String = ""

    override fun toString(): String = "ApplicationInfo($packageName, $dataDir)"
}
