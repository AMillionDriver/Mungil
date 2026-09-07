package com.mungil.browser

import com.mungil.browser.ExternalIntentPolicy.Decision.BLOCK
import com.mungil.browser.ExternalIntentPolicy.Decision.LOAD_IN_WEBVIEW
import com.mungil.browser.ExternalIntentPolicy.Decision.OPEN_EXTERNAL
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalIntentPolicyTest {

    private fun assertDecision(expected: ExternalIntentPolicy.Decision, url: String) {
        assertEquals(url, expected, ExternalIntentPolicy.decide(url))
    }

    @Test
    fun httpsUrlLoadsInWebView() {
        assertDecision(LOAD_IN_WEBVIEW, "https://example.com/video/123")
    }

    @Test
    fun httpUrlLoadsInWebView() {
        assertDecision(LOAD_IN_WEBVIEW, "http://example.com/video/123")
    }

    @Test
    fun schemeComparisonIsCaseInsensitive() {
        assertDecision(LOAD_IN_WEBVIEW, "HTTPS://Example.com/Video")
    }

    @Test
    fun httpUrlContainingPlayStoreHostStillLoadsInWebView() {
        assertDecision(LOAD_IN_WEBVIEW, "https://evil.example/?next=play.google.com/store")
    }

    @Test
    fun mailtoOpensExternally() {
        assertDecision(OPEN_EXTERNAL, "mailto:someone@example.com")
    }

    @Test
    fun mailtoWithUppercaseSchemeOpensExternally() {
        assertDecision(OPEN_EXTERNAL, "MAILTO:someone@example.com")
    }

    @Test
    fun telWithSpacesAndPunctuationOpensExternally() {
        assertDecision(OPEN_EXTERNAL, "tel:+62 812-3456-7890")
    }

    @Test
    fun smsOpensExternally() {
        assertDecision(OPEN_EXTERNAL, "sms:+628123456?body=hi")
    }

    @Test
    fun geoOpensExternally() {
        assertDecision(OPEN_EXTERNAL, "geo:-6.2088,106.8456?q=Jakarta")
    }

    @Test
    fun marketSchemeIsBlocked() {
        assertDecision(BLOCK, "market://details?id=com.example.app")
    }

    @Test
    fun playStoreHostOnNonHttpSchemeIsBlocked() {
        assertDecision(BLOCK, "someapp://open?ref=play.google.com")
    }

    @Test
    fun tiktokSchemeIsBlocked() {
        assertDecision(BLOCK, "tiktok://user/profile/123")
    }

    @Test
    fun snssdk1180SchemeIsBlocked() {
        assertDecision(BLOCK, "snssdk1180://user/profile")
    }

    @Test
    fun snssdk1233SchemeIsBlocked() {
        assertDecision(BLOCK, "snssdk1233://user/profile")
    }

    @Test
    fun intentSchemeIsBlocked() {
        assertDecision(BLOCK, "intent://scan/#Intent;scheme=zxing;package=com.evil;end")
    }

    @Test
    fun fileSchemeIsBlocked() {
        assertDecision(BLOCK, "file:///data/data/com.mungil.browser/shared_prefs/mungil_browser_prefs.xml")
    }

    @Test
    fun contentSchemeIsBlocked() {
        assertDecision(BLOCK, "content://com.mungil.browser.provider/secrets")
    }

    @Test
    fun javascriptSchemeIsBlocked() {
        assertDecision(BLOCK, "javascript:alert(document.cookie)")
    }

    @Test
    fun dataSchemeIsBlocked() {
        assertDecision(BLOCK, "data:text/html,<script>alert(1)</script>")
    }

    @Test
    fun blobSchemeIsBlocked() {
        assertDecision(BLOCK, "blob:https://example.com/6f1a2b3c")
    }

    @Test
    fun unknownThirdPartySchemeIsBlocked() {
        assertDecision(BLOCK, "whatsapp://send?text=hello")
    }

    @Test
    fun urlWithoutSchemeIsBlocked() {
        assertDecision(BLOCK, "example.com/no-scheme")
    }

    @Test
    fun missingSchemeDelimiterIsBlocked() {
        assertDecision(BLOCK, "://missing-scheme")
    }

    @Test
    fun schemeStartingWithDigitIsBlocked() {
        assertDecision(BLOCK, "1http://digit-first")
    }

    @Test
    fun emptyInputIsBlocked() {
        assertDecision(BLOCK, "")
    }

    @Test
    fun whitespaceInputIsBlocked() {
        assertDecision(BLOCK, "   ")
    }
}
