package com.mungil.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeUrlPolicyTest {

    private fun assertTrustworthy(reportedUrl: String, actualWebViewUrl: String?) {
        assertTrue(
            "harus dipercaya: reported=$reportedUrl actual=$actualWebViewUrl",
            BridgeUrlPolicy.isTrustworthy(reportedUrl, actualWebViewUrl)
        )
    }

    private fun assertNotTrustworthy(reportedUrl: String, actualWebViewUrl: String?) {
        assertFalse(
            "harus ditolak: reported=$reportedUrl actual=$actualWebViewUrl",
            BridgeUrlPolicy.isTrustworthy(reportedUrl, actualWebViewUrl)
        )
    }

    @Test
    fun exactSameUrlIsTrustworthy() {
        assertTrustworthy("https://example.com/watch", "https://example.com/watch")
    }

    @Test
    fun sameOriginWithDifferentPathIsTrustworthy() {
        assertTrustworthy("https://example.com/a/b", "https://example.com/c")
    }

    @Test
    fun sameOriginWithQueryAndFragmentIsTrustworthy() {
        assertTrustworthy("https://example.com/watch?v=1#t=20", "https://example.com/feed")
    }

    @Test
    fun hostComparisonIsCaseInsensitive() {
        assertTrustworthy("https://EXAMPLE.com/x", "https://example.com/")
    }

    @Test
    fun differentHostIsNotTrustworthy() {
        assertNotTrustworthy("https://maybank.co.id/login", "https://attacker.example/phish")
    }

    @Test
    fun subdomainIsNotTrustworthy() {
        assertNotTrustworthy("https://login.example.com/", "https://example.com/")
    }

    @Test
    fun lookalikeHostIsNotTrustworthy() {
        assertNotTrustworthy("https://example.com.attacker.net/", "https://example.com/")
    }

    @Test
    fun differentSchemeIsNotTrustworthy() {
        assertNotTrustworthy("https://example.com/", "http://example.com/")
    }

    @Test
    fun differentPortIsNotTrustworthy() {
        assertNotTrustworthy("https://example.com:8443/x", "https://example.com/")
    }

    @Test
    fun userInfoInReportedUrlIsNotTrustworthy() {
        assertNotTrustworthy("https://user:pass@example.com/", "https://example.com/")
    }

    @Test
    fun javascriptSchemeReportedIsNotTrustworthy() {
        assertNotTrustworthy("javascript:alert(document.cookie)", "https://example.com/")
    }

    @Test
    fun dataSchemeReportedIsNotTrustworthy() {
        assertNotTrustworthy("data:text/html,<h1>fake</h1>", "https://example.com/")
    }

    @Test
    fun fileSchemeReportedIsNotTrustworthy() {
        assertNotTrustworthy("file:///sdcard/Download/fake.html", "https://example.com/")
    }

    @Test
    fun customSchemeReportedIsNotTrustworthy() {
        assertNotTrustworthy("tiktok://user/123", "https://example.com/")
    }

    @Test
    fun nullActualUrlIsNotTrustworthy() {
        assertNotTrustworthy("https://example.com/", null)
    }

    @Test
    fun blankActualUrlIsNotTrustworthy() {
        assertNotTrustworthy("https://example.com/", "   ")
    }

    @Test
    fun blankReportedUrlIsNotTrustworthy() {
        assertNotTrustworthy("", "https://example.com/")
    }

    @Test
    fun actualUrlWithoutSchemeIsNotTrustworthy() {
        assertNotTrustworthy("https://example.com/", "example.com/page")
    }

    @Test
    fun reportedUrlWithoutAuthorityIsNotTrustworthy() {
        assertNotTrustworthy("https://", "https://example.com/")
    }
}
