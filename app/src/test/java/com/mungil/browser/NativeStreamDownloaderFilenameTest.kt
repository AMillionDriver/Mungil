package com.mungil.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeStreamDownloaderFilenameTest {

    private companion object {
        const val LONG_TITLE_LENGTH = 100
        const val MAX_BASE_LENGTH = 60
    }

    private fun baseOf(fileName: String): String =
        fileName.replace(Regex("_\\d+\\.[A-Za-z0-9]+$"), "")

    @Test
    fun nullTitleFallsBackToDefaultName() {
        val result = NativeStreamDownloader.sanitizeFilename(null, ".mp4")
        assertTrue(result, result.matches(Regex("^Mungil_Media_\\d+\\.mp4$")))
    }

    @Test
    fun blankTitleFallsBackToDefaultName() {
        val result = NativeStreamDownloader.sanitizeFilename("   ", ".mp4")
        assertTrue(result, result.matches(Regex("^Mungil_Media_\\d+\\.mp4$")))
    }

    @Test
    fun illegalCharactersBecomeUnderscores() {
        val result = NativeStreamDownloader.sanitizeFilename("My Great Clip!", ".mp4")
        assertTrue(result, result.matches(Regex("^My Great Clip_\\d+\\.mp4$")))
    }

    @Test
    fun hyphenAndSpaceArePreserved() {
        val result = NativeStreamDownloader.sanitizeFilename("Kimi no Na wa - 1080p", ".mp4")
        assertEquals("Kimi no Na wa - 1080p", baseOf(result))
    }

    @Test
    fun consecutiveUnderscoresCollapseToOne() {
        val result = NativeStreamDownloader.sanitizeFilename("a!!!b", ".mp4")
        assertTrue(result, result.matches(Regex("^a_b_\\d+\\.mp4$")))
    }

    @Test
    fun baseNameIsCappedAtSixtyCharacters() {
        val result = NativeStreamDownloader.sanitizeFilename("a".repeat(LONG_TITLE_LENGTH), ".mp4")
        assertEquals(MAX_BASE_LENGTH, baseOf(result).length)
    }

    @Test
    fun extensionWithoutLeadingDotIsNormalized() {
        val result = NativeStreamDownloader.sanitizeFilename("clip", "mp3")
        assertTrue(result, result.endsWith(".mp3"))
        assertFalse(result, result.contains(".."))
    }

    @Test
    fun extensionWithLeadingDotIsNotDoubled() {
        val result = NativeStreamDownloader.sanitizeFilename("clip", ".mp3")
        assertTrue(result, result.endsWith(".mp3"))
        assertFalse(result, result.contains(".."))
    }

    @Test
    fun exactBannedSlugFallsBack() {
        val result = NativeStreamDownloader.sanitizeFilename("video", ".mp4")
        assertTrue(result, result.matches(Regex("^Mungil_Media_\\d+\\.mp4$")))
    }

    @Test
    fun bannedSlugIsCaseInsensitive() {
        val result = NativeStreamDownloader.sanitizeFilename("VIDEO", ".mp4")
        assertTrue(result, result.matches(Regex("^Mungil_Media_\\d+\\.mp4$")))
    }

    @Test
    fun titleContainingBannedWordAsSubstringIsKept() {
        val result = NativeStreamDownloader.sanitizeFilename("Home Alone 1080p", ".mp4")
        assertEquals("Home Alone 1080p", baseOf(result))
    }

    @Test
    fun bannedSlugWithTrailingPunctuationFallsBack() {
        val result = NativeStreamDownloader.sanitizeFilename("Video.", ".mp4")
        assertTrue(result, result.matches(Regex("^Mungil_Media_\\d+\\.mp4$")))
    }

    @Test
    fun bannedSlugWrappedInUnderscoresFallsBack() {
        val result = NativeStreamDownloader.sanitizeFilename("_video_", ".mp4")
        assertTrue(result, result.matches(Regex("^Mungil_Media_\\d+\\.mp4$")))
    }

    @Test
    fun bannedSlugWithTrailingSymbolFallsBack() {
        val result = NativeStreamDownloader.sanitizeFilename("watch!", ".mp4")
        assertTrue(result, result.matches(Regex("^Mungil_Media_\\d+\\.mp4$")))
    }
}
