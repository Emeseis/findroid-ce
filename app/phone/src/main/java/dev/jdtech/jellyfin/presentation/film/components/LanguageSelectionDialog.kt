package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.FindroidMediaStream
import dev.jdtech.jellyfin.models.ItemPreferenceDto
import dev.jdtech.jellyfin.presentation.theme.spacings

@Composable
fun LanguageSelectionDialog(
    availableAudio: List<FindroidMediaStream>,
    availableSubtitles: List<FindroidMediaStream>,
    currentPreference: ItemPreferenceDto?,
    groupingId: java.util.UUID,
    isSeries: Boolean = false,
    onConfirm: (ItemPreferenceDto) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedAudioIndex by remember { mutableStateOf(currentPreference?.audioIndex) }
    var selectedAudioTitle by remember { mutableStateOf(currentPreference?.audioTitle) }
    var selectedAudioLanguage by remember { mutableStateOf(currentPreference?.audioLanguage) }

    var selectedSubtitleIndex by remember { mutableStateOf(currentPreference?.subtitleIndex) }
    var selectedSubtitleTitle by remember { mutableStateOf(currentPreference?.subtitleTitle) }
    var selectedSubtitleLanguage by remember { mutableStateOf(currentPreference?.subtitleLanguage) }
    var selectedSubtitleIsForced by remember { mutableStateOf(currentPreference?.subtitleIsForced) }
    var subtitleNoneSelected by remember { mutableStateOf(currentPreference?.subtitleLanguage == "none") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(CoreR.string.language_preference)) },
        text = {
            LazyColumn {
                item {
                    Text(stringResource(CoreR.string.audio), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(MaterialTheme.spacings.small))
                }
                item {
                    LanguageOption(
                        title = stringResource(CoreR.string.server_default),
                        isSelected = selectedAudioIndex == null && selectedAudioLanguage == null && selectedAudioTitle == null,
                        onClick = {
                            selectedAudioIndex = null
                            selectedAudioLanguage = null
                            selectedAudioTitle = null
                        }
                    )
                }
                items(availableAudio, key = { it.index ?: it.hashCode() }) { stream ->
                    val streamTitle = stream.displayTitle?.takeIf { it.isNotBlank() } ?: stream.title
                    val isSelected = if (!isSeries && selectedAudioIndex != null && stream.index != null) {
                        selectedAudioIndex == stream.index
                    } else {
                        selectedAudioLanguage == stream.language && (selectedAudioTitle == streamTitle || selectedAudioTitle == stream.title || (selectedAudioTitle.isNullOrBlank() && streamTitle.isBlank()))
                    }
                    LanguageOption(
                        title = streamTitle.ifBlank { stream.language },
                        isSelected = isSelected,
                        onClick = {
                            selectedAudioIndex = stream.index
                            selectedAudioLanguage = stream.language
                            selectedAudioTitle = streamTitle.takeIf { it.isNotBlank() }
                        }
                    )
                }
                item {
                    Spacer(Modifier.height(MaterialTheme.spacings.medium))
                    HorizontalDivider()
                    Spacer(Modifier.height(MaterialTheme.spacings.medium))
                    Text(stringResource(CoreR.string.subtitle), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(MaterialTheme.spacings.small))
                }
                item {
                    LanguageOption(
                        title = stringResource(CoreR.string.server_default),
                        isSelected = !subtitleNoneSelected && selectedSubtitleIndex == null && selectedSubtitleLanguage == null && selectedSubtitleTitle == null,
                        onClick = {
                            subtitleNoneSelected = false
                            selectedSubtitleIndex = null
                            selectedSubtitleLanguage = null
                            selectedSubtitleTitle = null
                            selectedSubtitleIsForced = null
                        }
                    )
                }
                item {
                    LanguageOption(
                        title = stringResource(CoreR.string.none),
                        isSelected = subtitleNoneSelected,
                        onClick = {
                            subtitleNoneSelected = true
                            selectedSubtitleIndex = null
                            selectedSubtitleLanguage = "none"
                            selectedSubtitleTitle = null
                            selectedSubtitleIsForced = null
                        }
                    )
                }
                items(availableSubtitles, key = { it.index ?: it.hashCode() }) { stream ->
                    val streamTitle = stream.displayTitle?.takeIf { it.isNotBlank() } ?: stream.title
                    val isSelected = !subtitleNoneSelected && if (!isSeries && selectedSubtitleIndex != null && stream.index != null) {
                        selectedSubtitleIndex == stream.index
                    } else {
                        selectedSubtitleLanguage == stream.language &&
                                (selectedSubtitleTitle == streamTitle || selectedSubtitleTitle == stream.title || (selectedSubtitleTitle.isNullOrBlank() && streamTitle.isBlank())) &&
                                (selectedSubtitleIsForced == null || stream.isForced == selectedSubtitleIsForced)
                    }
                    val forcedTag = stringResource(CoreR.string.forced_tag)
                    val baseTitle = streamTitle.ifBlank { stream.language }
                    val displayTitle = if (stream.isForced && !baseTitle.contains(forcedTag, ignoreCase = true)) {
                        "$baseTitle ($forcedTag)"
                    } else baseTitle

                    LanguageOption(
                        title = displayTitle,
                        isSelected = isSelected,
                        onClick = {
                            subtitleNoneSelected = false
                            selectedSubtitleIndex = stream.index
                            selectedSubtitleLanguage = stream.language
                            selectedSubtitleTitle = streamTitle.takeIf { it.isNotBlank() }
                            selectedSubtitleIsForced = stream.isForced
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        ItemPreferenceDto(
                            id = groupingId,
                            audioLanguage = selectedAudioLanguage,
                            audioTitle = selectedAudioTitle,
                            audioIndex = if (isSeries) null else selectedAudioIndex,
                            subtitleLanguage = if (subtitleNoneSelected) "none" else selectedSubtitleLanguage,
                            subtitleTitle = if (subtitleNoneSelected) null else selectedSubtitleTitle,
                            subtitleIndex = if (subtitleNoneSelected || isSeries) null else selectedSubtitleIndex,
                            subtitleIsForced = if (subtitleNoneSelected) null else selectedSubtitleIsForced
                        )
                    )
                }
            ) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun LanguageOption(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = isSelected, onClick = onClick)
        Text(text = title, style = MaterialTheme.typography.bodyMedium)
    }
}
