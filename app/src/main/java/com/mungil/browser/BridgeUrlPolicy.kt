package com.mungil.browser

object BridgeUrlPolicy {

    private val WEB_SCHEMES = setOf("http", "https")
    private val SCHEME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*$")
    private const val AUTHORITY_DELIMITERS = "/?#"

    fun isTrustworthy(reportedUrl: String, actualWebViewUrl: String?): Boolean {
        val actual = actualWebViewUrl
        if (actual.isNullOrBlank() || reportedUrl.isBlank()) return false
        val reportedOrigin = originOf(reportedUrl)
        val actualOrigin = originOf(actual)
        return reportedOrigin != null && reportedOrigin == actualOrigin
    }

    private fun originOf(url: String): String? {
        val trimmed = url.trim()
        val separator = trimmed.indexOf(':')
        val scheme = if (separator > 0) trimmed.substring(0, separator).lowercase() else ""
        val rest = if (separator > 0) trimmed.substring(separator + 1) else ""
        val authority = if (rest.startsWith("//")) {
            rest.substring(2).takeWhile { it !in AUTHORITY_DELIMITERS }.lowercase()
        } else {
            ""
        }
        val isWebOrigin = SCHEME_PATTERN.matches(scheme) &&
            WEB_SCHEMES.contains(scheme) &&
            authority.isNotEmpty()
        return if (isWebOrigin) "$scheme://$authority" else null
    }
}
