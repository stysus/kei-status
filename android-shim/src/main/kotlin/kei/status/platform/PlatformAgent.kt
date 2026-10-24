package kei.status.platform

/**
 * Defines the `http.agent` system property Android ships, so extensions reading it resolve here too.
 *
 * Android's runtime always defines `http.agent` as the device's default agent string, and several
 * extensions build their default User-Agent from it with `!!` - the mccms and sinmh themes,
 * Tappytoon, The Library of Ohara. A plain JVM does not define the property, so those sources throw
 * NullPointerException the first time their headers are built - before any request is made - and
 * read on the page as a broken extension when the extension is fine on a device.
 *
 * The value is a plausible device agent rather than the app's own UA: what the harness owes these
 * sources is a non-null string in the shape Android produces, since that is all `!!` and the sites
 * they ask see.
 */
object PlatformAgent {

    private const val ANDROID_AGENT =
        "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Build/UQ1A.240205.002)"

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return

        synchronized(this) {
            if (installed) return
            System.setProperty("http.agent", ANDROID_AGENT)
            installed = true
        }
    }
}
