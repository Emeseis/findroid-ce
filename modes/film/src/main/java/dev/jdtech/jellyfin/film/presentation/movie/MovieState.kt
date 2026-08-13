package dev.jdtech.jellyfin.film.presentation.movie

import dev.jdtech.jellyfin.models.FindroidItemPerson
import dev.jdtech.jellyfin.models.FindroidMediaStream
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.ItemPreferenceDto
import dev.jdtech.jellyfin.models.VideoMetadata

data class MovieState(
    val movie: FindroidMovie? = null,
    val videoMetadata: VideoMetadata? = null,
    val actors: List<FindroidItemPerson> = emptyList(),
    val director: FindroidItemPerson? = null,
    val writers: List<FindroidItemPerson> = emptyList(),
    val displayExtraInfo: Boolean = false,
    val itemPreference: ItemPreferenceDto? = null,
    val availableAudioStreams: List<FindroidMediaStream> = emptyList(),
    val availableSubtitleStreams: List<FindroidMediaStream> = emptyList(),
    val error: Exception? = null,
)
