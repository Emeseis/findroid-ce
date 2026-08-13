package dev.jdtech.jellyfin.models

import androidx.room.Entity
import java.util.UUID

@Entity(
    tableName = "item_preferences",
    primaryKeys = ["userId", "id"],
)
data class ItemPreferenceDto(
    val userId: UUID = UNSPECIFIED_USER_ID,
    val id: UUID,
    val audioLanguage: String? = null,
    val audioTitle: String? = null,
    val audioIndex: Int? = null,
    val subtitleLanguage: String? = null,
    val subtitleTitle: String? = null,
    val subtitleIndex: Int? = null,
    val subtitleIsForced: Boolean? = null,
) {
    companion object {
        /** Replaced by the repository before persistence. */
        val UNSPECIFIED_USER_ID: UUID = UUID(0L, 0L)
    }
}
