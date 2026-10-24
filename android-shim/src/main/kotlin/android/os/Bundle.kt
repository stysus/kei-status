package android.os

/**
 * Minimal stand-in for android.os.Bundle.
 *
 * Only referenced by Activity lifecycle signatures; the harness never saves or restores state. The
 * one reader modelled is [getStringArrayList], which the anonymous receivers sources register look
 * into for their delivered payload - reading it back null is the truthful answer, because nothing
 * in the harness ever delivers into a Bundle.
 */
class Bundle {

    fun getStringArrayList(name: String?): ArrayList<String>? = null
}
