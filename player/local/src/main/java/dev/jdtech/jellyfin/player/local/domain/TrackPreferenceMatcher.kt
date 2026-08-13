package dev.jdtech.jellyfin.player.local.domain

import dev.jdtech.jellyfin.player.core.domain.models.PreferenceTrack
import dev.jdtech.jellyfin.models.languageTagsMatch

data class PlayerTrackDescriptor(
    val title: String?,
    val language: String?,
    val isForced: Boolean,
    val isExternal: Boolean,
    val codec: String? = null,
    val channelCount: Int? = null,
)

/** Matches Jellyfin track metadata to player tracks without comparing unrelated numeric ids. */
object TrackPreferenceMatcher {
    fun findCanonicalTrack(
        tracks: List<PreferenceTrack>,
        streamIndex: Int?,
        language: String?,
        title: String?,
        isForced: Boolean?,
    ): PreferenceTrack? {
        streamIndex?.let { index -> tracks.firstOrNull { it.index == index }?.let { return it } }

        return tracks.maxByOrNull { track ->
            matchScore(
                target = track,
                candidate = PlayerTrackDescriptor(
                    title = title,
                    language = language,
                    isForced = isForced ?: track.isForced,
                    isExternal = track.isExternal,
                ),
                forcedRequired = isForced != null,
                externalRequired = false,
            )
        }?.takeIf { track ->
            languageMatches(track.language, language) || titleMatches(track.title, title)
        }
    }

    fun findPlayerTrackIndex(
        target: PreferenceTrack,
        tracks: List<PlayerTrackDescriptor>,
    ): Int? = tracks.indices
        .map { index ->
            index to matchScore(
                target = target,
                candidate = tracks[index],
                forcedRequired = true,
                externalRequired = true,
            )
        }
        .filter { (_, score) -> score >= MIN_ACCEPTABLE_SCORE }
        .maxByOrNull { (_, score) -> score }
        ?.first

    fun findCanonicalTrackForPlayer(
        playerTrack: PlayerTrackDescriptor,
        tracks: List<PreferenceTrack>,
    ): PreferenceTrack? = tracks
        .map { track ->
            track to matchScore(
                target = track,
                candidate = playerTrack,
                forcedRequired = true,
                externalRequired = true,
            )
        }
        .filter { (_, score) -> score >= MIN_ACCEPTABLE_SCORE }
        .maxByOrNull { (_, score) -> score }
        ?.first

    private fun matchScore(
        target: PreferenceTrack,
        candidate: PlayerTrackDescriptor,
        forcedRequired: Boolean,
        externalRequired: Boolean,
    ): Int {
        if (forcedRequired && target.isForced != candidate.isForced) return NO_MATCH
        if (externalRequired && target.isExternal != candidate.isExternal) return NO_MATCH

        var score = 0
        if (target.isForced == candidate.isForced) score += 4
        if (target.isExternal == candidate.isExternal) score += 4
        if (languageMatches(target.language, candidate.language)) score += 20
        if (titleMatches(target.title, candidate.title)) score += 40
        if (valueMatches(target.codec, candidate.codec)) score += 16
        if (target.channelCount != null && target.channelCount == candidate.channelCount) score += 12
        return score
    }

    private fun valueMatches(first: String?, second: String?): Boolean =
        !first.isNullOrBlank() && !second.isNullOrBlank() && first.equals(second, ignoreCase = true)

    private fun languageMatches(first: String?, second: String?): Boolean =
        languageTagsMatch(first, second)

    private fun titleMatches(first: String?, second: String?): Boolean {
        val a = normalizeTitle(first)
        val b = normalizeTitle(second)
        if (a == null || b == null) return false
        return a == b || a.contains(b) || b.contains(a)
    }

    private fun normalizeTitle(value: String?): String? = value
        ?.trim()
        ?.lowercase()
        ?.replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private const val NO_MATCH = Int.MIN_VALUE
    private const val MIN_ACCEPTABLE_SCORE = 20
}
