package dev.jdtech.jellyfin.models

import java.util.Locale

fun localizedLanguageName(languageTag: String?): String? {
    val tag = languageTag
        ?.trim()
        ?.replace('_', '-')
        ?.takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) && it != "none" }
        ?: return null
    val locale = Locale.forLanguageTag(tag)
    val displayName = locale.getDisplayLanguage(Locale.getDefault())
    return displayName
        .takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) }
        ?.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        ?: tag
}
