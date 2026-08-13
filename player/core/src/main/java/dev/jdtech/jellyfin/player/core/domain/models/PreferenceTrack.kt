package dev.jdtech.jellyfin.player.core.domain.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Canonical server metadata used when a track selection is persisted from the player. */
@Parcelize
data class PreferenceTrack(
    val index: Int?,
    val title: String?,
    val language: String?,
    val isForced: Boolean = false,
    val isExternal: Boolean = false,
) : Parcelable
