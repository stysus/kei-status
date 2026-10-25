package kei.status.engine

import android.content.ActivityNotFoundException
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.interceptor.AntiBotChallengeException
import java.util.concurrent.TimeoutException

/** Outcome of classifying a thrown exception. */
data class Diagnosis(
    val status: Status,
    val message: String,
    val chain: List<String>,
)

/**
 * Maps thrown exceptions to a status, a one-line reason, and the cause chain.
 *
 * The chain matters: reflection and static initialisers hide the interesting exception several
 * levels down, and a health report is useless if it only says "InvocationTargetException".
 */
object ExceptionDiagnosis {

    fun diagnose(t: Throwable): Diagnosis {
        val chain = flatten(t)
        val rendering = chain.map { "${it::class.java.name}: ${it.message ?: "<no message>"}" }

        val status = when {
            chain.any { it is AntiBotChallengeException } -> Status.BLOCKED
            chain.any { needsWebViewSession(it) } -> Status.BLOCKED
            chain.any { needsLogin(it) } -> Status.BLOCKED
            chain.any { geoRestricted(it) } -> Status.BLOCKED
            chain.any { it is ActivityNotFoundException } -> Status.BLOCKED
            chain.any { it is HttpException && it.code == 429 } -> Status.THROTTLED
            chain.any { it is UnsupportedOperationException || it is NotImplementedError } -> Status.SKIP
            chain.any { unavailablePlatform(it) != null } -> Status.UNSUPPORTED
            chain.any { it is TimeoutException } -> Status.FAIL
            else -> Status.FAIL
        }

        val reason = reason(status, chain, t)

        // When the reason is the exception's own message, the chain entry for that exception only
        // repeats it ("java.io.IOException: <same text>"). Keep the entries that add context - the
        // wrapping causes and the class names of a different message - and drop the restatement.
        val notes = rendering
            .filterNot { it.substringAfter(": ", "") == reason }
            .distinct()

        return Diagnosis(status, reason, notes)
    }

    private fun reason(status: Status, chain: List<Throwable>, t: Throwable): String = when (status) {
        Status.BLOCKED -> when {
            chain.any { it is AntiBotChallengeException } ->
                chain.filterIsInstance<AntiBotChallengeException>()
                    .firstOrNull()
                    ?.let { "${it.provider} anti-bot challenge; the source needs a browser session that passes it" }
                    ?: "anti-bot challenge; the source needs a browser session that passes it"

            chain.any { it is ActivityNotFoundException } ->
                "the source opens one of its own activities (a verification or captcha step) that the sweep cannot run"

            chain.any { it.message?.startsWith("Captcha needs interaction") == true } ->
                "the site's captcha needs a human the sweep cannot provide; the source is not broken"

            // Attribution mirrors diagnose(): a message that asks for a WebView session can also
            // mention a session or token, and that verdict is about the browser, not credentials.
            chain.any { needsWebViewSession(it) } ->
                "the site requires a WebView session the sweep's browser did not complete; the source is not broken"

            chain.any { needsLogin(it) } ->
                "the source requires login or credentials the sweep cannot provide; the source is not broken"

            chain.any { geoRestricted(it) } ->
                "the site restricts by region and the sweep runs outside the allowed area; the source is not broken"

            else -> "the site requires a WebView session the sweep's browser did not complete; the source is not broken"
        }

        Status.THROTTLED -> chain.filterIsInstance<HttpException>()
            .firstOrNull { it.code == 429 }
            ?.retryAfterSeconds
            ?.let { "the site is rate-limiting this sweep (HTTP 429, retry after ${it}s); the source was not checked" }
            ?: "the site is rate-limiting this sweep (HTTP 429); the source was not checked"

        Status.SKIP -> "not supported by this source"

        Status.UNSUPPORTED -> chain.firstNotNullOfOrNull { unavailablePlatform(it) }
            ?: "requires a platform class the harness does not provide"

        else -> when (val missing = chain.firstOrNull { it is NoClassDefFoundError || it is ClassNotFoundException }) {
            null -> rootMessage(chain) ?: reasonWithoutMessage(t)
            else -> "missing class: ${missing.message}"
        }
    }

    /**
     * The one-line reason for an exception whose chain carries no message at all.
     *
     * Kotlin's `!!` throws a NullPointerException with no message, and a report that says only
     * "NullPointerException" is unusable for triage: locating the failure would take a local re-run
     * with tracing on. The extension's own stack frame names the file and line, so the recorded
     * state is actionable on its own - the same purpose the cause chain serves for wrapped
     * exceptions.
     */
    private fun reasonWithoutMessage(t: Throwable): String {
        val frame = t.stackTrace.firstOrNull {
            it.className.startsWith("eu.kanade.tachiyomi.") || it.className.startsWith("keiyoushi.")
        } ?: return t::class.java.simpleName

        val location = when (val file = frame.fileName) {
            null -> "${frame.className.substringAfterLast('.')}.${frame.methodName}()"
            else -> "${frame.className.substringAfterLast('.')}.${frame.methodName}($file:${frame.lineNumber})"
        }
        return "${t::class.java.simpleName} at $location"
    }

    /**
     * True when the exception says the site demands a WebView session the sweep could not finish.
     *
     * `keiyoushi.utils.WebViewTimeoutException` is not on the engine class path - it lives in the
     * extension repo's core, which the harness only ever reads compiled - so the chain is matched
     * on the class name and on the message that class throws. Extensions re-throw the same text in
     * their own words (Comix), and the pam and templescan themes throw the verification sentence
     * verbatim, so the message carries the verdict the app would reach: the site answers a real
     * browser, and this sweep's browser did not pass it.
     */
    private fun needsWebViewSession(t: Throwable): Boolean =
        t::class.java.name == "keiyoushi.utils.WebViewTimeoutException" ||
            t.message?.startsWith("Timed out waiting for WebView") == true ||
            t.message?.contains("pass the site's verification", ignoreCase = true) == true ||
            t.message?.startsWith("Captcha needs interaction") == true

    /**
     * True when the exception says the source demands an account the sweep cannot provide.
     *
     * Roughly forty sources refuse anonymous access with a message in their own language, so
     * this is keyword matching - the same approach `needsWebViewSession` and `lockedChapter`
     * already take. The Latin keywords are matched on word boundaries, because "log in" is a
     * substring of "catalog in", "sign in" of "designed in", and a parse error can quote a
     * `loginUrl` field whose drift is a real failure, not a demand. The token clause covers
     * sources that word the demand without "login" at all (hdoujin: "Open webview to refresh
     * token"). Inflections get their own spellings rather than a stem: Portuguese drops the
     * h, so "autentic"/"autentiqu" (autenticado, autentique) never touch English
     * "authentication", and Turkish conjugates "oturum aç" apart from "giriş yap". Chinese
     * names the password (密码) without ever saying 登录, and Madokami's English demand
     * ("input your credentials") says neither login nor sign in. Two texts deserve a note: a
     * 404 that the extension re-throws with "authorize via WebView" advice (hentailib,
     * slashlib) is the author's own verdict that the path refuses anonymous access, and the
     * Arabic message reaches this check through "تسجيل الدخول" even when it ships no English
     * aside.
     */
    private fun needsLogin(t: Throwable): Boolean {
        val text = t.message?.lowercase() ?: return false
        if (loginDemand.containsMatchIn(text)) return true
        return ("webview" in text && ("token" in text || "refresh" in text || "session" in text)) ||
            "авториз" in text ||
            "giriş yap" in text ||
            "oturum aç" in text ||
            "đăng nhập" in text ||
            "登入" in text ||
            "登录" in text ||
            "密码" in text ||
            "تسجيل الدخول" in text ||
            "autentic" in text ||
            "autentiqu" in text ||
            (("e-mail" in text || "email" in text) && "senha" in text)
    }

    private val loginDemand = Regex("""\b(?:login|log\s+in|sign\s+in|password|credentials)\b""")

    /**
     * True when the site refuses the request because the sweep's IP is outside the region it
     * serves.
     *
     * The Japanese services in the repo answer an outside IP with one of three English
     * sentences, so the literals are exact. Broader keywords ("country", "region") were
     * rejected: a parse error can quote a field or URL that carries them, and the geo refusal
     * is the only site behavior here that is permanent - unlike a login demand, no
     * configuration the sweep could fake would lift it. A source whose refusal reads
     * differently joins these literals when the data shows the sentence, the same way the
     * other message checks grew.
     */
    private fun geoRestricted(t: Throwable): Boolean {
        val text = t.message?.lowercase() ?: return false
        return "country is not supported" in text ||
            "only available in japan" in text ||
            "only be used from japan" in text
    }

    /** Walks causes, including the ones reflection and static initialisers hide. */
    private fun flatten(t: Throwable): List<Throwable> {
        val seen = mutableListOf<Throwable>()
        var current: Throwable? = t

        while (current != null && seen.none { it === current }) {
            seen += current
            current = when (current) {
                is java.lang.reflect.InvocationTargetException -> current.targetException ?: current.cause
                is ExceptionInInitializerError -> current.exception ?: current.cause
                else -> current.cause
            }
        }

        return seen
    }

    private fun rootMessage(chain: List<Throwable>): String? =
        chain.asReversed().firstNotNullOfOrNull { it.message }

    /**
     * Describes a platform class or member the harness cannot supply, or null if the gap is a real
     * bug.
     *
     * The distinction matters: an extension that needs `android.graphics.pdf.PdfRenderer` is not
     * broken, it needs a platform this tool does not emulate. A missing `keiyoushi.*` or
     * `okhttp3.*` class would instead mean the harness classpath is wrong.
     *
     * The same reasoning applies one level down. A shim that provides `android.net.Uri` but not
     * `Uri.encode` fails at link time with [NoSuchMethodError], whose message names the member
     * rather than a class. It is the same gap and the same verdict: the extension is fine on a
     * device, and calling it broken would blame it for what the shim does not implement.
     */
    private fun unavailablePlatform(t: Throwable): String? = when (t) {
        is NoClassDefFoundError, is ClassNotFoundException -> missingClass(t.message)
        is NoSuchMethodError, is NoSuchFieldError, is AbstractMethodError -> missingMember(t.message)
        else -> null
    }

    private fun missingClass(message: String?): String? {
        val className = (message ?: return null).replace('/', '.')

        return when {
            className.startsWith("android.") || className.startsWith("androidx.") ->
                "requires an Android class the harness does not provide: $className"

            className.startsWith("app.cash.quickjs.") ->
                "requires the native QuickJS library, which has no JVM build: $className"

            else -> null
        }
    }

    /**
     * The platform member a link failure names, or null when the member belongs somewhere else.
     *
     * The JVM builds these messages as `<return type> <owner>.<member>` followed by the parameter
     * list - `'byte[] android.util.Base64.decode(byte[], int)'` - so the owner is the last
     * space-separated token before `(`, minus its own member name. A field reference has no
     * parameter list and keeps the same shape without it. HotSpot quotes the whole signature;
     * the trim removes the quotes from either end of what is left.
     */
    private fun missingMember(message: String?): String? {
        val reference = (message ?: return null)
            .replace('/', '.')
            .substringBefore('(')
            .substringAfterLast(' ')
            .trim('\'')
        val owner = reference.substringBeforeLast('.')

        return when {
            owner.startsWith("android.") || owner.startsWith("androidx.") ->
                "requires an Android method or field the harness does not provide: $reference"

            owner.startsWith("app.cash.quickjs.") ->
                "requires the native QuickJS library, which has no JVM build: $reference"

            else -> null
        }
    }
}
