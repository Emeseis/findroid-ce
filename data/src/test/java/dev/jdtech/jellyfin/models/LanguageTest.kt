package dev.jdtech.jellyfin.models

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LanguageTest {
    @Test
    fun `matches ISO 639 two and three letter language codes`() {
        assertEquals(true, languageTagsMatch("ja", "jpn"))
        assertEquals(true, languageTagsMatch("pt-BR", "por"))
        assertEquals(false, languageTagsMatch("jpn", "por"))
    }

    @Test
    fun `removes duplicate localized and English language names`() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("pt-BR"))
            assertEquals(
                "Português - AAC - Stereo - Padrão",
                compactTrackDisplayName(
                    languageTag = "por",
                    title = "Português - Portuguese - AAC - Stereo - Padrão",
                )
            )
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `prefixes language when custom title does not contain it`() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("pt-BR"))
            assertEquals("Português — Comentários", compactTrackDisplayName("por", "Comentários"))
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun `keeps only one forced marker at the end of subtitle title`() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("pt-BR"))
            assertEquals(
                "Nome da faixa - Português - ASS (Forçada)",
                compactSubtitleDisplayName(
                    languageTag = "por",
                    title = "Nome da faixa - Português - Forçado - ASS",
                    isForced = true,
                    forcedLabel = "Forçada",
                )
            )
        } finally {
            Locale.setDefault(previousLocale)
        }
    }
}
