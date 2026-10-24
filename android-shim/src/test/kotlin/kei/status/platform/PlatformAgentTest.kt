package kei.status.platform

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformAgentTest {

    @Test
    fun `the http agent property Android defines but the JVM does not now resolves`() {
        PlatformAgent.install()

        // Extensions build their default User-Agent from this property with `!!` - mccms, sinmh,
        // tappytoon and others. Android always defines it; a plain JVM does not, and reading it
        // there throws NullPointerException before any request is made.
        val agent = System.getProperty("http.agent")
        assertNotNull("http.agent is not defined", agent)
        // It is sent to sites as a User-Agent, so it has to look like the device's own.
        assertTrue("not an Android agent string: $agent", agent!!.startsWith("Dalvik/"))
    }

    @Test
    fun `installing twice does not change the value`() {
        PlatformAgent.install()
        val first = System.getProperty("http.agent")
        assertNotNull(first)

        PlatformAgent.install()

        assertSame(first, System.getProperty("http.agent"))
    }
}
