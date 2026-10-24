package android.content

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One Application serves every module in the JVM, and a module's background work (filter
 * prefetches on GlobalScope) can still be running while the next module's constructor resolves its
 * own preferences - so getSharedPreferences is reached from more than one thread.
 */
class ContextPrefsConcurrencyTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun `concurrent lookups return one instance per file name`() {
        val app = Application().apply { attachRoot(temporary.root) }
        val threads = 16
        val repeats = 200
        val names = listOf("SharedPreferences", "source_4638673959522768501", "addin")

        // Every instance ever handed out, per file name. A second instance for one name means two
        // callers can hold two views of the same preference file and silently clobber each other.
        val seen = ConcurrentHashMap<String, MutableSet<Any>>()
        val start = CountDownLatch(1)

        val pool = Executors.newFixedThreadPool(threads)
        try {
            val futures = (0 until threads).map { worker ->
                pool.submit(Callable {
                    start.await()
                    repeat(repeats) { round ->
                        names.forEachIndexed { index, name ->
                            if ((worker + round + index) % 2 == 0) {
                                app.getSharedPreferences(name, 0)
                                    .edit()
                                    .putBoolean("probe-$worker", true)
                                    .apply()
                            }
                            seen.getOrPut(name) { ConcurrentHashMap.newKeySet() }
                                .add(app.getSharedPreferences(name, 0))
                        }
                    }
                })
            }
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        names.forEach { name ->
            assertEquals("lookups for $name produced ${seen[name]?.size} instances", 1, seen[name]?.size)
        }

        // The values the workers wrote must all have survived the concurrent edits.
        val shared = seen.getValue("SharedPreferences").single() as SharedPreferences
        for (worker in 0 until threads) {
            if (worker % 2 == 0) {
                assertTrue("probe-$worker was lost", shared.getBoolean("probe-$worker", false))
            }
        }
    }
}
