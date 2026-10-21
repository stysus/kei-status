package kei.status.platform

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Adds the crypto provider Android ships, so JCE transformations extensions use resolve here too.
 *
 * Android bundles BouncyCastle, and extensions rely on the transformations only it provides:
 * `CryptoAES` asks for `AES/CBC/PKCS7PADDING`, Mangago for `AES/CBC/ZEROBYTEPADDING`. Neither exists
 * in the JVM's SunJCE, and both libraries swallow the `NoSuchAlgorithmException` - `CryptoAES`
 * returns an empty string - so the failure surfaces as the extension's own "failed to decrypt"
 * message. That reads as a broken extension when the extension is fine.
 *
 * Appended rather than inserted first: SunJCE keeps answering everything it already handles, so a
 * source that passes today cannot change behaviour, and BouncyCastle only supplies what was missing.
 */
object PlatformCrypto {

    @Volatile
    private var installed = false

    fun install() {
        if (installed) return

        synchronized(this) {
            if (installed) return
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
            installed = true
        }
    }
}
