package android

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.text.Html
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Guards the JVM descriptors extensions are compiled against.
 *
 * A shim member can exist, compile, and still be unusable: when the descriptor differs from
 * android.jar the call site fails at link time with `NoSuchMethodError`. Kotlin hides that class of
 * defect well - `var typeface` generates a `void` setter, which reads fine and links wrong - so the
 * return types are asserted explicitly, with the same lookup the JVM performs at run time.
 */
class PlatformLinkageTest {

    @Test
    fun `paint setTypeface returns the typeface instead of void`() {
        val method = Paint::class.java.getMethod("setTypeface", Typeface::class.java)

        assertEquals(Typeface::class.java, method.returnType)
        assertEquals(Typeface::class.java, Paint::class.java.getMethod("getTypeface").returnType)
    }

    @Test
    fun `uri exposes the encode static`() {
        val encode = Uri::class.java.getMethod("encode", String::class.java)

        assertEquals(String::class.java, encode.returnType)
    }

    @Test
    fun `uri Builder chains the calls sources assemble requests with`() {
        val builder = Uri.Builder::class.java

        assertEquals(Uri.Builder::class.java, builder.getMethod("appendPath", String::class.java).returnType)
        assertEquals(
            Uri.Builder::class.java,
            builder.getMethod("appendQueryParameter", String::class.java, String::class.java).returnType,
        )
        assertEquals(Uri::class.java, builder.getMethod("build").returnType)
        assertEquals(Uri.Builder::class.java, Uri::class.java.getMethod("buildUpon").returnType)
    }

    @Test
    fun `base64 offers the byte array overload as well as the string one`() {
        val decode = Base64::class.java.getMethod(
            "decode",
            ByteArray::class.java,
            Int::class.javaPrimitiveType,
        )

        assertEquals(ByteArray::class.java, decode.returnType)
    }

    @Test
    fun `base64 decodes raw bytes and a string the same way`() {
        val raw = "AAEC".toByteArray()

        assertEquals(listOf<Byte>(0, 1, 2), Base64.decode(raw, Base64.DEFAULT).toList())
        assertEquals(
            Base64.decode("AAEC", Base64.DEFAULT).toList(),
            Base64.decode(raw, Base64.DEFAULT).toList(),
        )
    }

    @Test
    fun `bitmap exposes the pixel array surface`() {
        val bitmap = Bitmap::class.java
        val ints = Int::class.javaPrimitiveType!!

        assertNotNull(bitmap.getMethod("getPixels", IntArray::class.java, ints, ints, ints, ints, ints, ints))
        assertEquals(
            Bitmap::class.java,
            bitmap.getMethod("createBitmap", IntArray::class.java, ints, ints, Bitmap.Config::class.java).returnType,
        )
        // The strided overload: createBitmap(int[], int, int, int, int, Config).
        assertEquals(
            Bitmap::class.java,
            bitmap.getMethod(
                "createBitmap",
                IntArray::class.java,
                ints, ints, ints, ints,
                Bitmap.Config::class.java,
            ).returnType,
        )
    }

    @Test
    fun `rect exposes the float centre`() {
        assertEquals(Float::class.javaPrimitiveType, Rect::class.java.getMethod("exactCenterX").returnType)
        assertEquals(Float::class.javaPrimitiveType, Rect::class.java.getMethod("exactCenterY").returnType)
    }

    @Test
    fun `context exposes the external cache and the application info`() {
        assertEquals(File::class.java, Context::class.java.getMethod("getExternalCacheDir").returnType)
        assertEquals(ApplicationInfo::class.java, Context::class.java.getMethod("getApplicationInfo").returnType)
    }

    @Test
    fun `application info exposes dataDir as a field, which is how bytecode reads it`() {
        val field = ApplicationInfo::class.java.getField("dataDir")

        assertEquals(String::class.java, field.type)
    }

    @Test
    fun `html fromHtml is a static returning Spanned`() {
        val method = Html::class.java.getMethod("fromHtml", String::class.java, Int::class.javaPrimitiveType)

        assertEquals(Spanned::class.java, method.returnType)
        assertEquals(Spanned::class.java, Html::class.java.getMethod("fromHtml", String::class.java).returnType)
    }

    @Test
    fun `static layout keeps the deprecated constructor the text interceptor calls`() {
        val constructor = StaticLayout::class.java.getConstructor(
            CharSequence::class.java,
            TextPaint::class.java,
            Int::class.javaPrimitiveType,
            Layout.Alignment::class.java,
            Float::class.javaPrimitiveType,
            Float::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )

        assertEquals(Int::class.javaPrimitiveType, StaticLayout::class.java.getMethod("getHeight").returnType)
        assertNotNull(constructor)
    }

    @Test
    fun `layout alignment is a nested enum, which is how the bytecode names it`() {
        assertEquals("android.text.Layout\$Alignment", Layout.Alignment::class.java.name)
        assertEquals(
            Layout.Alignment::class.java,
            Layout.Alignment::class.java.getField("ALIGN_NORMAL").type,
        )
    }
}
