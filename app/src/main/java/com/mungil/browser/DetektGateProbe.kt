package com.mungil.browser

internal object DetektGateProbe {

    // TODO: probe gerbang detekt, file ini dihapus setelah CI terbukti merah
    fun probe(): String {
        try {
            return "probe"
        } catch (e: Exception) {
        }
        return "unreachable"
    }
}
