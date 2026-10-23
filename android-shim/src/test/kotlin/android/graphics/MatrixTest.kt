package android.graphics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android's matrix operations return `boolean`. For the composing calls that is only a success flag
 * - Skia returns false when it cannot allocate - while `mapRect` returns `rectStaysRect()`, which
 * says whether the corners still form an axis-aligned rectangle.
 */
class MatrixTest {

    @Test
    fun `the composing operations report success`() {
        val matrix = Matrix()
        val other = Matrix().apply { postScale(2f, 2f) }

        assertTrue(matrix.postScale(2f, 3f))
        assertTrue(matrix.postScale(2f, 3f, 1f, 1f))
        assertTrue(matrix.preScale(2f, 3f))
        assertTrue(matrix.postTranslate(4f, 5f))
        assertTrue(matrix.preTranslate(-4f, -5f))
        assertTrue(matrix.postRotate(90f))
        assertTrue(matrix.postRotate(90f, 1f, 2f))
        assertTrue(matrix.postConcat(other))
        assertTrue(matrix.preConcat(other))
    }

    @Test
    fun `a rect survives scale, translate, and quarter turns`() {
        assertTrue(Matrix().rectStaysRect())
        assertTrue(Matrix().apply { postScale(2f, 3f) }.rectStaysRect())
        assertTrue(Matrix().apply { postTranslate(4f, 5f) }.rectStaysRect())
        assertTrue(Matrix().apply { postRotate(90f) }.rectStaysRect())
        assertTrue(Matrix().apply { postRotate(180f) }.rectStaysRect())
        assertTrue(Matrix().apply { postRotate(-90f) }.rectStaysRect())
    }

    @Test
    fun `a rect does not survive an arbitrary rotation`() {
        assertFalse(Matrix().apply { postRotate(45f) }.rectStaysRect())
        assertFalse(Matrix().apply { postRotate(10f, 2f, 2f) }.rectStaysRect())
    }

    @Test
    fun `mapRect transforms the corners in place`() {
        val rect = RectF(1f, 1f, 3f, 5f)

        assertTrue(Matrix().apply { postScale(2f, 3f) }.mapRect(rect))

        assertEquals(RectF(2f, 3f, 6f, 15f), rect)
    }

    @Test
    fun `mapRect after a quarter turn swaps the axes`() {
        val rect = RectF(0f, 0f, 2f, 1f)

        assertTrue(Matrix().apply { postRotate(90f) }.mapRect(rect))

        assertEquals(RectF(-1f, 0f, 0f, 2f), rect)
    }

    @Test
    fun `mapRect reports false when the result is no longer axis-aligned`() {
        assertFalse(Matrix().apply { postRotate(45f) }.mapRect(RectF(1f, 1f, 3f, 3f)))
    }

    @Test
    fun `mapRect reports true for the identity`() {
        val rect = RectF(1f, 2f, 3f, 4f)

        assertTrue(Matrix().mapRect(rect))

        assertEquals(RectF(1f, 2f, 3f, 4f), rect)
    }
}
