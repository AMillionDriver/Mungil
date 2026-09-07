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
        if (trimmed.isEmpty()) return Decision.BLOCK

        val scheme = schemeOf(trimmed) ?: return Decision.BLOCK
        if (WEB_SCHEMES.contains(scheme)) return Decision.LOAD_IN_WEBVIEW
        if (trimmed.contains(BLOCKED_HOST_KEYWORD, ignoreCase = true)) return Decision.BLOCK
        if (BLOCKED_SCHEMES.contains(scheme)) return Decision.BLOCK
        if (EXTERNAL_SCHEMES.contains(scheme)) return Decision.OPEN_EXTERNAL

        return Decision.BLOCK
    }

    private fun schemeOf(url: String): String? {
        val separator = url.indexOf(':')
        if (separator <= 0) return null
        val candidate = url.substring(0, separator)
        return if (SCHEME_PATTERN.matches(candidate)) candidate.lowercase() else null
    }
}
