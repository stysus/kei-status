package android.view

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `getParent` answers a [ViewParent], not a [ViewGroup]: Android declares the wider type, and every
 * source that wants a container casts. The membership is what makes the cast work, so both halves
 * are checked here.
 */
class ViewContractTest {

    @Test
    fun `getParent is null until the view is added`() {
        assertNull(View().getParent())
    }

    @Test
    fun `a group is a view parent, and the parent is the group that added the view`() {
        val group = ViewGroup()
        val child = View()

        group.addView(child)

        assertTrue(child.getParent() is ViewParent)
        assertSame(group, child.getParent())
        assertSame(group, child.getParent() as ViewGroup)
    }

    @Test
    fun `removeView detaches the child`() {
        val group = ViewGroup()
        val child = View()

        group.addView(child)
        group.removeView(child)

        assertNull(child.getParent())
    }

    @Test
    fun `removeCallbacks answers true for every action, including null`() {
        assertTrue(View().removeCallbacks(null))
        assertTrue(View().removeCallbacks(Runnable { }))
    }
}
