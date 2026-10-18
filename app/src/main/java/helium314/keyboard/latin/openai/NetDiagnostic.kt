// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.openai

import android.os.Build
import helium314.keyboard.latin.BuildConfig

/** step of a network action, for messages and the "Copy details" text */
enum class NetStep { CHECK }

/**
 * What went wrong, without secrets: the step, the kind of failure (class of the exception and of its root cause, never
 * the message), the HTTP status if there is one, for a blocked address its host and path (no query), and the time in ms.
 */
class NetDiagnostic(
    val step: NetStep,
    /** technical cause in English, for "Copy details" */
    val cause: String,
    val httpStatus: Int? = null,
    val errorCode: String? = null,
    val elapsedMs: Long,
    /** the cause in plain words for the screen; null: [cause] is shown */
    val kind: Kind? = null,
) {
    enum class Kind { INTERNET_OFF }

    /** one line: "UnknownHostException, 123 ms" */
    fun short(): String = buildString {
        append(cause)
        httpStatus?.let { append(", HTTP ").append(it) }
        errorCode?.let { append(", ").append(it) }
        append(", ").append(elapsedMs).append(" ms")
    }

    /** text for "Copy details": nothing secret (no key, no text), extra lines e.g. about the proxy */
    fun details(extra: List<String> = emptyList()): String = (listOf(
        "AI Keyboard ${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "step: ${step.name.lowercase().replace('_', ' ')}",
        "cause: $cause",
    ) + listOfNotNull(
        httpStatus?.let { "HTTP status: $it" },
        errorCode?.let { "error: $it" },
    ) + listOf(
        "time: $elapsedMs ms",
    ) + extra).joinToString("\n")

    /** the user certificates of a VPN or proxy are not trusted (only system certificates) */
    val looksLikeCertificateProblem get() = "Cert" in cause || "SSLHandshake" in cause

    companion object {
        fun of(step: NetStep, e: Throwable, elapsedMs: Long): NetDiagnostic = when (e) {
            is NetworkBlockedException -> NetDiagnostic(step, when (e.reason) {
                NetworkBlockedException.Reason.INTERNET_OFF -> "internet access switched off"
                NetworkBlockedException.Reason.NOT_ALLOWED -> "address not allowed: ${e.target ?: "?"}"
                NetworkBlockedException.Reason.REDIRECT -> "redirect not followed"
                NetworkBlockedException.Reason.BAD_PROXY -> "proxy not usable: ${e.target ?: "?"}"
                NetworkBlockedException.Reason.VOICE_API_OFF -> "voice through OpenAI switched off or no key"
                NetworkBlockedException.Reason.TEXT_API_OFF -> "AI text functions switched off or no key"
            }, e.status, null, elapsedMs, if (e.reason == NetworkBlockedException.Reason.INTERNET_OFF) Kind.INTERNET_OFF else null)
            else -> NetDiagnostic(step, exceptionName(e), null, null, elapsedMs)
        }

        /** "SSLHandshakeException (CertPathValidatorException)": class and root cause class, never messages */
        fun exceptionName(e: Throwable): String {
            var root: Throwable = e
            while (root.cause != null && root.cause !== root) root = root.cause!!
            val name = e.javaClass.simpleName.ifEmpty { e.javaClass.name }
            return if (root === e || root.javaClass == e.javaClass) name else "$name (${root.javaClass.simpleName})"
        }
    }
}
