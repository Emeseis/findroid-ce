package dev.jdtech.jellyfin.player.local.domain

import androidx.media3.common.C
import androidx.media3.common.Tracks
import dev.jdtech.jellyfin.models.localizedLanguageName

fun List<Tracks.Group>.getTrackNames(): Array<String> {
    return this.map { group ->
        val format = group.mediaTrackGroup.getFormat(0)
        val parts = mutableListOf<String>()

        val label = format.label?.takeIf { it.isNotBlank() }
        val langDisplay = localizedLanguageName(format.language)

        if (label != null) {
            parts.add(label)
        }
        if (langDisplay != null && (label == null || !label.contains(langDisplay, ignoreCase = true))) {
            parts.add(langDisplay)
        }
        val codec = format.codecs?.takeIf { it.isNotBlank() }
        if (codec != null && (label == null || !label.contains(codec, ignoreCase = true))) {
            parts.add(codec)
        }

        val baseTitle = parts.joinToString(separator = " - ")
        val isForced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
        if (isForced && !baseTitle.contains("forced", ignoreCase = true)) {
            "$baseTitle (Forced)"
        } else {
            baseTitle
        }
    }.toTypedArray()
}
