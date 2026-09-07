package com.mungil.browser

object BridgeUrlPolicy {

    private val WEB_SCHEMES = setOf("http", "https")
    private val SCHEME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*$")
    private const val AUTHORITY_DELIMITERS = "/?#"

    fun isTrustworthy(reportedUrl: String, actualWebViewUrl: String?): Boolean {
        if (actualWebViewUrl.isNullOrBlank()) return false
        if (reportedUrl.isBlank()) return false

        val reported = originOf(reportedUrl) ?: return false
        val actual = originOf(actualWebViewUrl) ?: return false
        return reported == actual
    }

    private fun originOf(url: String): String? {
        val trimmed = url.trim()
        val separator = trimmed.indexOf(':')
        if (separator <= 0) return null

        val scheme = trimmed.substring(0, separator)
        if (!SCHEME_PATTERN.matches(scheme)) return null
        val normalizedScheme = scheme.lowercase()
        if (!WEB_SCHEMES.contains(normalizedScheme)) return null

        val rest = trimmed.substring(separator + 1)
        if (!rest.startsWith("//")) return null

        val authority = rest.substring(2).takeWhile { it !in AUTHORITY_DELIMITERS }
        if (authority.isEmpty()) return null

        return "$normalizedScheme://${authority.lowercase()}"
    }
}
