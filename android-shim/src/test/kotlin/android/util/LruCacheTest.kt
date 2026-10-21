package android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LruCacheTest {

    @Test
    fun `the least recently used entry is evicted`() {
        val cache = LruCache<String, String>(2)
        cache.put("a", "1")
        cache.put("b", "2")

        // Reading "a" makes "b" the least recently used, so "b" goes when "c" arrives.
        cache.get("a")
        cache.put("c", "3")

        assertEquals("1", cache.get("a"))
        assertEquals("3", cache.get("c"))
        assertNull(cache.get("b"))
        assertEquals(1, cache.evictionCount())
    }

    @Test
    fun `sizeOf weights an entry and entryRemoved reports evictions and replacements`() {
        val removed = mutableListOf<String>()
        val cache = object : LruCache<String, String>(4) {
            override fun sizeOf(key: String, value: String): Int = value.length

            override fun entryRemoved(
                evicted: Boolean,
                key: String,
                oldValue: String,
                newValue: String?,
            ) {
                removed += if (evicted) "evicted:$key" else "replaced:$key"
            }
        }

        cache.put("a", "xx")
        cache.put("b", "yy")
        cache.put("c", "zzz")

        // 2 + 2 + 3 overflows the budget of 4, so both older entries have to go; `size` is the
        // weighted cost, not the number of entries.
        assertEquals(3, cache.size())
        assertEquals(listOf("evicted:a", "evicted:b"), removed)
        assertNull(cache.get("a"))

        assertNull(cache.put("b", "yy"))
        assertEquals("yy", cache.put("b", "zz"))
        assertEquals("replaced:b", removed.last())
    }

    @Test
    fun `a cache that creates on miss caches the value it created`() {
        val cache = object : LruCache<String, String>(2) {
            override fun create(key: String): String? = "$key!"
        }

        assertEquals("k!", cache.get("k"))
        assertEquals("k!", cache.get("k"))
        assertEquals(1, cache.createCount())
        assertTrue(cache.snapshot().containsKey("k"))
    }
}
