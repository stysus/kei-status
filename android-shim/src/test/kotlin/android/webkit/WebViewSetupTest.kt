package android.webkit

import kei.status.webkit.ChromeUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CompletableFuture

/**
 * Pins the classification of a browser that died while the page's DevTools setup was still arming:
 * the load must surface [ChromeUnavailableException] - which diagnose reports as UNSUPPORTED -
 * instead of running the navigation into a dead session and letting it time out into a FAIL that
 * blames the extension for harness infrastructure. Runs without a browser; the dead setup is
 * planted directly.
 */
class WebViewSetupTest {

    @Test
    fun `a setup that died with the browser reports unavailable and never navigates`() {
        val dead = CompletableFuture<Void>()
        dead.completeExceptionally(ChromeUnavailableException("the browser died mid-setup"))
        val view = WebView(null)

        var navigated = false
        try {
            view.afterSetup(dead) { navigated = true }
            fail("a setup that died with the browser must throw")
        } catch (expected: ChromeUnavailableException) {
            assertEquals("the browser died mid-setup", expected.cause?.message)
        }
        assertEquals(false, navigated)
        view.destroy()
    }

    @Test
    fun `a completed setup runs the navigation`() {
        val view = WebView(null)
        var navigated = false
        view.afterSetup(CompletableFuture.completedFuture(null)) { navigated = true }
        assertEquals(true, navigated)
        view.destroy()
    }
}
