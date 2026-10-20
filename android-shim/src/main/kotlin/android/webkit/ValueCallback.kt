package android.webkit

/** Callback interface used by android.webkit APIs that complete asynchronously. */
fun interface ValueCallback<T> {
    fun onReceiveValue(value: T)
}
