package dev.jdtech.jellyfin.player.local.domain

import dev.jdtech.jellyfin.player.core.domain.models.PreferenceTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackPreferenceMatcherTest {
    @Test
    fun `matches an internal track by language when player order differs`() {
        val target = preference(language = "por", title = "Português - Portuguese - SUBRIP")
        val playerTracks = listOf(
            player(language = "ita", title = "Italiano"),
            player(language = "por", title = "Português"),
        )

        assertEquals(1, TrackPreferenceMatcher.findPlayerTrackIndex(target, playerTracks))
    }

    @Test
    fun `does not confuse normal and forced external subtitles`() {
        val normal = preference(language = "por", title = "Português", external = true)
        val playerTracks = listOf(
            player(language = "por", title = "Português (Forced)", forced = true, external = true),
            player(language = "por", title = "Português", external = true),
        )

        assertEquals(1, TrackPreferenceMatcher.findPlayerTrackIndex(normal, playerTracks))
    }

    @Test
    fun `does not match an external preference to an internal track`() {
        val external = preference(language = "por", title = "Português", external = true)

        assertNull(
            TrackPreferenceMatcher.findPlayerTrackIndex(
                external,
                listOf(player(language = "por", title = "Português")),
            )
        )
    }

    @Test
    fun `resolves canonical movie track by Jellyfin stream index`() {
        val portuguese = preference(index = 7, language = "por", title = "Português")
        val tracks = listOf(preference(index = 3, language = "ita"), portuguese)

        assertEquals(
            portuguese,
            TrackPreferenceMatcher.findCanonicalTrack(
                tracks = tracks,
                streamIndex = 7,
                language = null,
                title = null,
                isForced = false,
            )
        )
    }

    private fun preference(
        index: Int? = null,
        language: String? = null,
        title: String? = null,
        forced: Boolean = false,
        external: Boolean = false,
    ) = PreferenceTrack(index, title, language, forced, external)

    private fun player(
        language: String?,
        title: String?,
        forced: Boolean = false,
        external: Boolean = false,
    ) = PlayerTrackDescriptor(title, language, forced, external)
}
