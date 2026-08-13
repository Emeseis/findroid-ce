package dev.jdtech.jellyfin.presentation.film.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.models.ItemPreferenceDto
import dev.jdtech.jellyfin.models.localizedLanguageName
import dev.jdtech.jellyfin.presentation.theme.spacings

@Composable
fun LanguagePreferenceRow(
    preference: ItemPreferenceDto?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val audioLanguageDisplay = localizedLanguageName(preference?.audioLanguage)
    val subtitleLanguageDisplay = localizedLanguageName(preference?.subtitleLanguage)

    val audioText = if (preference != null) {
        val language = audioLanguageDisplay
        val title = preference.audioTitle
        when {
            title != null && language != null -> stringResource(CoreR.string.track_with_title, language, title)
            title != null -> title
            language != null -> language
            else -> stringResource(CoreR.string.server_default)
        }
    } else {
        stringResource(CoreR.string.server_default)
    }

    val subtitleText = if (preference != null) {
        val language = preference.subtitleLanguage
        val title = preference.subtitleTitle
        val isForced = preference.subtitleIsForced
        when {
            language == "none" -> stringResource(CoreR.string.none)
            title != null && subtitleLanguageDisplay != null -> {
                val base = stringResource(CoreR.string.track_with_title, subtitleLanguageDisplay, title)
                if (isForced == true) "$base (${stringResource(CoreR.string.forced_tag)})" else base
            }
            title != null -> if (isForced == true) "$title (${stringResource(CoreR.string.forced_tag)})" else title
            subtitleLanguageDisplay != null -> if (isForced == true) "$subtitleLanguageDisplay (${stringResource(CoreR.string.forced_tag)})" else subtitleLanguageDisplay
            else -> stringResource(CoreR.string.server_default)
        }
    } else {
        stringResource(CoreR.string.server_default)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = MaterialTheme.spacings.extraSmall),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.extraSmall)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_volume),
                contentDescription = null,
                modifier = Modifier.padding(end = MaterialTheme.spacings.small),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(CoreR.string.audio_language, audioText),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_closed_caption),
                contentDescription = null,
                modifier = Modifier.padding(end = MaterialTheme.spacings.small),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(CoreR.string.subtitle_language, subtitleText),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
