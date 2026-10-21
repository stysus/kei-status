package kei.status.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class PlatformCryptoTest {

    @Test
    fun `the transformations Android provides but the JVM does not now resolve`() {
        PlatformCrypto.install()

        // `CryptoAES` (used by nekopost, lunaranime and others) and Mangago ask for these. Without a
        // provider both throw NoSuchAlgorithmException, which those libraries swallow - so the
        // extension looks broken instead of the harness looking incomplete.
        for (transformation in listOf("AES/CBC/PKCS7PADDING", "AES/CBC/ZEROBYTEPADDING")) {
            val cipher = Cipher.getInstance(transformation)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(ByteArray(32), "AES"),
                IvParameterSpec(ByteArray(16)),
            )
            assertNotNull("$transformation did not initialise", cipher)
        }
    }

    @Test
    fun `installing twice is harmless and does not reorder providers`() {
        PlatformCrypto.install()
        val first = Security.getProviders().first().name

        PlatformCrypto.install()

        // Appended, not inserted: SunJCE must keep answering first so no source that passes today
        // changes behaviour.
        assertEquals("SUN", first)
        assertEquals(1, Security.getProviders().count { it.name == "BC" })
    }
}
