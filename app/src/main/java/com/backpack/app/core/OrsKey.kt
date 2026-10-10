package com.backpack.app.core

/**
 * 1.5.0: recognising an openrouteservice key (e.g. on the clipboard after
 * "copy" on the openrouteservice account page). Two shapes are known: the
 * current one ("eyJvcmci..." = base64 of {"org":...}) and the older 56-digit
 * hex one. Anything else can still be pasted by hand. Plain JVM (tested).
 */
object OrsKey {
    private val CURRENT = Regex("^eyJvcmci[A-Za-z0-9+/=_-]{20,400}$")
    private val OLD_HEX = Regex("^[0-9a-f]{56}$")

    /** The clipboard text, if it looks like a key (spaces / quotes around it ignored). */
    fun fromClipboard(text: CharSequence?): String? {
        val t = text?.toString()?.trim()?.trim('"', '\'', ' ')?.trim() ?: return null
        return t.takeIf { CURRENT.matches(it) || OLD_HEX.matches(it) }
    }

    /** Typed or pasted by hand: no spaces inside, a plausible length. */
    fun plausible(text: String): Boolean {
        val t = text.trim()
        return t.length in 20..400 && t.none { it.isWhitespace() }
    }

    enum class Check { OK, REFUSED, QUOTA, UNREACHABLE }

    /** HTTP answer of the test route -> what it says about the key. */
    fun checkFromHttp(code: Int?): Check = when (code) {
        null -> Check.UNREACHABLE
        in 200..299 -> Check.OK
        401, 403 -> Check.REFUSED
        429 -> Check.QUOTA // the key is known; only today's quota is used up
        else -> Check.UNREACHABLE
    }
}
