package android.os

/**
 * Marker stand-in for android.os.Parcelable.
 *
 * Its only load-bearing job is assignability: `Intent.putExtra(String, Parcelable)` resolves
 * against this type, so anything an extension hands to an extra - MangaLivre's verification
 * [ResultReceiver] - has to implement it or the call site fails to verify at link time. The
 * framework's marshalling methods are deliberately absent: no parcel is ever written, and an
 * implementer that overrides nothing still links.
 */
interface Parcelable
