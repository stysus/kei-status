package android.util

import java.util.LinkedHashMap

/**
 * Stand-in for android.util.LruCache, ported from the platform class.
 *
 * Extensions use it as a plain in-memory cache - a page batch, a tag id, a parsed response - so the
 * behaviour that matters is the platform's: the least recently *used* entry is evicted, not the
 * oldest inserted one, `sizeOf` can weight an entry as more than one slot, and `entryRemoved` runs
 * outside the lock. The counters and `snapshot` are part of the public surface and cost nothing to
 * keep, so a source that reads them links instead of failing.
 */
open class LruCache<K, V>(private var maxSize: Int) {

    private val map: LinkedHashMap<K, V> = LinkedHashMap(0, 0.75f, true)

    private var size = 0

    private var putCount = 0

    private var createCount = 0

    private var evictionCount = 0

    private var hitCount = 0

    private var missCount = 0

    init {
        if (maxSize <= 0) throw IllegalArgumentException("maxSize <= 0")
    }

    /** Grows or shrinks the cache, evicting whatever no longer fits. */
    fun resize(newMaxSize: Int) {
        if (newMaxSize <= 0) throw IllegalArgumentException("maxSize <= 0")
        synchronized(this) { maxSize = newMaxSize }
        trimToSize(newMaxSize)
    }

    fun get(key: K): V? {
        if (key == null) throw NullPointerException("key == null")

        synchronized(this) {
            val mapValue = map[key]
            if (mapValue != null) {
                hitCount++
                return mapValue
            }
            missCount++
        }

        val createdValue = create(key) ?: return null

        val previous: V? = synchronized(this) {
            createCount++
            val old = map.put(key, createdValue)
            // Another thread got there first: keep its value and drop the one just created.
            if (old != null) map[key] = old else size += safeSizeOf(key, createdValue)
            old
        }

        if (previous != null) {
            entryRemoved(false, key, createdValue, previous)
            return previous
        }

        trimToSize(maxSize)
        return createdValue
    }

    fun put(key: K, value: V): V? {
        if (key == null || value == null) throw NullPointerException("key == null || value == null")

        val previous: V? = synchronized(this) {
            putCount++
            size += safeSizeOf(key, value)
            val old = map.put(key, value)
            if (old != null) size -= safeSizeOf(key, old)
            old
        }

        if (previous != null) entryRemoved(false, key, previous, value)

        trimToSize(maxSize)
        return previous
    }

    fun remove(key: K): V? {
        if (key == null) throw NullPointerException("key == null")

        val previous: V? = synchronized(this) {
            val old = map.remove(key)
            if (old != null) size -= safeSizeOf(key, old)
            old
        }

        if (previous != null) entryRemoved(false, key, previous, null)
        return previous
    }

    fun evictAll() {
        trimToSize(-1)
    }

    fun size(): Int = synchronized(this) { size }

    fun maxSize(): Int = synchronized(this) { maxSize }

    fun hitCount(): Int = synchronized(this) { hitCount }

    fun missCount(): Int = synchronized(this) { missCount }

    fun createCount(): Int = synchronized(this) { createCount }

    fun putCount(): Int = synchronized(this) { putCount }

    fun evictionCount(): Int = synchronized(this) { evictionCount }

    fun snapshot(): Map<K, V> = synchronized(this) { LinkedHashMap(map) }

    override fun toString(): String = synchronized(this) {
        val accesses = hitCount + missCount
        val hitPercent = if (accesses != 0) 100 * hitCount / accesses else 0
        "LruCache[maxSize=$maxSize,hits=$hitCount,misses=$missCount,hitRate=$hitPercent%]"
    }

    /**
     * Called after a cache miss to compute a value for the key, or null to cache nothing. The
     * default implementation caches nothing, as on the platform.
     */
    protected open fun create(key: K): V? = null

    /**
     * The cost of an entry in this cache, one slot by default. A negative cost is a bug in the
     * subclass, so it is rejected rather than allowed to corrupt [size].
     */
    protected open fun sizeOf(key: K, value: V): Int = 1

    /** Called when an entry is evicted or replaced, after the lock has been released. */
    protected open fun entryRemoved(evicted: Boolean, key: K, oldValue: V, newValue: V?) {}

    private fun trimToSize(newMaxSize: Int) {
        while (true) {
            val evicted = synchronized(this) {
                if (size < 0 || (map.isEmpty() && size != 0)) throw IllegalStateException("$this")
                if (size <= newMaxSize || map.isEmpty()) return
                val toEvict = map.entries.iterator().next()
                map.remove(toEvict.key)
                size -= safeSizeOf(toEvict.key, toEvict.value)
                evictionCount++
                toEvict.key to toEvict.value
            }

            entryRemoved(true, evicted.first, evicted.second, null)
        }
    }

    private fun safeSizeOf(key: K, value: V): Int {
        val result = sizeOf(key, value)
        if (result < 0) throw IllegalStateException("negative size: $key=$value")
        return result
    }
}
