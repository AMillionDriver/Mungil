package com.mungil.browser

object ExternalIntentPolicy {

    enum class Decision {
        LOAD_IN_WEBVIEW,
        OPEN_EXTERNAL,
        BLOCK
    }

    private val WEB_SCHEMES = setOf("http", "https")
    private val EXTERNAL_SCHEMES = setOf("mailto", "tel", "sms", "geo")
    private val BLOCKED_SCHEMES = setOf("market", "tiktok", "snssdk1180", "snssdk1233", "intent")
    private const val BLOCKED_HOST_KEYWORD = "play.google.com"
    private val SCHEME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*$")

    fun decide(url: String): Decision {
        val trimmed = url.trim()
        val scheme = schemeOf(trimmed)
        return when {
            scheme == null -> Decision.BLOCK
            WEB_SCHEMES.contains(scheme) -> Decision.LOAD_IN_WEBVIEW
            trimmed.contains(BLOCKED_HOST_KEYWORD, ignoreCase = true) -> Decision.BLOCK
            BLOCKED_SCHEMES.contains(scheme) -> Decision.BLOCK
            EXTERNAL_SCHEMES.contains(scheme) -> Decision.OPEN_EXTERNAL
            else -> Decision.BLOCK
        }
    }

    private fun schemeOf(url: String): String? {
        val separator = url.indexOf(':')
        val candidate = if (separator > 0) url.substring(0, separator) else ""
        return candidate.takeIf { SCHEME_PATTERN.matches(it) }?.lowercase()
    }
}
