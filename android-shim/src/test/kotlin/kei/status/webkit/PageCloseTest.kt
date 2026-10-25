package kei.status.webkit

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.URI

/**
 * A page whose DevTools socket already failed must still retire its Chromium target.
 *
 * `onFailure` sets the socket's `closed` flag without telling the browser anything, so a `close()`
 * that trusts that flag skips the HTTP target close and leaves an orphan tab in the browser - one
 * per socket failure, across a sweep that opens hundreds of pages. The flag only describes the
 * socket, never the target.
 */
class PageCloseTest {

    @Before
    fun requireBrowser() {
        assumeTrue("no Chromium available", runCatching { Chrome.port() }.isSuccess)
    }

    @After
    fun tearDown() = Chrome.stop()

    private fun listedTargets(): String =
        URI("http://127.0.0.1:${Chrome.port()}/json/list").toURL().readText()

    @Test
    fun `close retires the target even after the socket failed`() {
        val page = Chrome.newPage()

        // Simulate what WebSocket onFailure does: mark the socket dead and leave the target alive.
        Page::class.java.getDeclaredField("closed").apply { isAccessible = true }.set(page, true)

        page.close()

        // The target may take a moment to leave the list; poll rather than race it.
        var gone = false
        repeat(20) {
            gone = !listedTargets().contains(page.targetId)
            if (!gone) Thread.sleep(100)
        }
        assertTrue("target ${page.targetId} survived close()", gone)
    }
}
