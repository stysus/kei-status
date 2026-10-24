package android.os

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MangaLivre hands a [ResultReceiver] to its own verification activity through an Intent extra, and
 * its anonymous subclass reads a `getStringArrayList` back out of the delivered Bundle. The harness
 * never runs the activity, so only the linking surface has to exist - but it has to exist exactly:
 * `putExtra(String, Parcelable)` resolves against the receiver's declared type, so the stub must be
 * a [Parcelable], or the call site fails to verify at link time.
 */
class ResultReceiverTest {

    @Test
    fun `anonymous receiver subclasses construct and receive results`() {
        var received: Pair<Int, Bundle?>? = null
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                received = resultCode to resultData
            }
        }

        receiver.onReceiveResult(7, Bundle())

        assertEquals(7, received?.first)
        assertTrue(received?.second is Bundle)
    }

    @Test
    fun `receiver is a parcelable so intent extras link`() {
        val receiver = object : ResultReceiver(null) {}

        assertTrue(Parcelable::class.isInstance(receiver))

        val intent = Intent().putExtra("receiver", receiver)

        assertTrue(intent !== null)
    }

    @Test
    fun `bundle without a delivery reads back null`() {
        assertNull(Bundle().getStringArrayList("pages"))
    }
}
