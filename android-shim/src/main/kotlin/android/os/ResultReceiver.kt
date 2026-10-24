package android.os

/**
 * Stand-in for android.os.ResultReceiver.
 *
 * MangaLivre passes an anonymous subclass to its own verification activity through an Intent extra,
 * and its `onReceiveResult` override reads the delivered Bundle. The harness refuses the intent
 * before anything could call back, so only the construction and override surface is modelled -
 * which is exactly what bytecode needs to link: the receiver has to be a [Parcelable] for the
 * `putExtra(String, Parcelable)` call site to verify.
 */
open class ResultReceiver(private val handler: Handler?) : Parcelable {

    /** Nobody delivers into a harness Bundle; subclasses override this to receive a real result. */
    open fun onReceiveResult(resultCode: Int, resultData: Bundle?) = Unit
}
