package com.mungil.browser

object ExternalIntentPolicy {

    enum class Decision {
        LOAD_IN_WEBVIEW,
        OPEN_EXTERNAL,
        BLOCK
    }

    fun decide(url: String): Decision = TODO()
}
