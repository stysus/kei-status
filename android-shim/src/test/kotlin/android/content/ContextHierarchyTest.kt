package android.content

import android.app.Activity
import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextHierarchyTest {

    @Test
    fun `an activity is a context wrapper with readable resources`() {
        // Core code merges an Activity with the application context and is compiled to
        // `checkcast ContextWrapper` + `ContextWrapper.getResources()`. Without this hierarchy the
        // JVM cannot link the call, and the source fails before it runs.
        val activity: Activity = Activity()
        val context: ContextWrapper = activity

        val configuration = context.getResources().configuration

        assertEquals(
            Configuration.UI_MODE_NIGHT_UNDEFINED,
            configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK,
        )
        assertTrue(context.getBaseContext() == null)
    }

    @Test
    fun `a wrapper delegates to its base context`() {
        val base = android.app.Application()
        val wrapper = ContextWrapper(base)

        assertTrue(wrapper.getBaseContext() === base)
        assertTrue(wrapper.getResources() === base.getResources())
    }
}
