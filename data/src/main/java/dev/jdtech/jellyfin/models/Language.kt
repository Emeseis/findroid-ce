package dev.jdtech.jellyfin.models

import java.util.Locale

fun languageTagsMatch(first: String?, second: String?): Boolean {
    val firstCode = canonicalLanguageCode(first)
    val secondCode = canonicalLanguageCode(second)
    return firstCode != null && secondCode != null && firstCode == secondCode
}

fun canonicalLanguageCode(languageTag: String?): String? {
    val tag = languageTag
        ?.trim()
        ?.replace('_', '-')
        ?.takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) && it != "none" }
        ?: return null
    val locale = languageLocale(tag)
    return runCatching { locale.isO3Language.lowercase(Locale.ROOT) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: tag.substringBefore('-').lowercase(Locale.ROOT)
}

fun localizedLanguageName(languageTag: String?): String? {
    val tag = languageTag
        ?.trim()
        ?.replace('_', '-')
        ?.takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) && it != "none" }
        ?: return null
    val locale = languageLocale(tag)
    val displayName = locale.getDisplayLanguage(Locale.getDefault())
    return displayName
        .takeIf { it.isNotBlank() && !it.equals(tag, ignoreCase = true) }
        ?.replaceFirstChar { it.titlecase(Locale.getDefault()) }
        ?: tag
}

/** Removes duplicate localized/English language components without changing stored metadata. */
fun compactTrackDisplayName(languageTag: String?, title: String?): String? {
    val rawTitle = title?.trim()?.takeIf { it.isNotEmpty() }
    val localized = localizedLanguageName(languageTag)
    if (rawTitle == null) return localized

    val locale = languageTag
        ?.trim()
        ?.replace('_', '-')
        ?.let(::languageLocale)
    val languageNames = listOfNotNull(
        localized,
        locale?.getDisplayLanguage(Locale.ENGLISH),
        locale?.getDisplayLanguage(locale),
    ).filter { it.isNotBlank() }.distinctBy { it.lowercase() }

    val components = rawTitle.split(Regex("\\s+(?:—|-)\\s+"))
    val seenLanguage = mutableSetOf<String>()
    val compacted = components.filter { component ->
        val languageName = languageNames.firstOrNull { it.equals(component, ignoreCase = true) }
        languageName == null || seenLanguage.add("language")
    }
    val compactTitle = compacted.joinToString(" - ")
    val alreadyContainsLanguage = components.any { component ->
        languageNames.any { it.equals(component, ignoreCase = true) }
    }

    return if (localized != null && !alreadyContainsLanguage) "$localized — $compactTitle" else compactTitle
}

fun compactSubtitleDisplayName(
    languageTag: String?,
    title: String?,
    isForced: Boolean,
    forcedLabel: String,
): String? {
    val titleWithoutForcedMarker = title
        ?.split(Regex("\\s+(?:—|-)\\s+"))
        ?.filterNot { component ->
            component.trim().trim('(', ')', '[', ']').let { marker ->
                marker.equals("forced", ignoreCase = true) ||
                    marker.equals("forçado", ignoreCase = true) ||
                    marker.equals("forçada", ignoreCase = true) ||
                    marker.equals(forcedLabel, ignoreCase = true)
            }
        }
        ?.joinToString(" - ")

    val base = compactTrackDisplayName(languageTag, titleWithoutForcedMarker)
    return if (isForced && base != null) "$base ($forcedLabel)" else base
}

private fun languageLocale(languageTag: String): Locale {
    val locale = Locale.forLanguageTag(languageTag)
    val language = languageTag.substringBefore('-')
    if (language.length != 3) return locale

    return Locale.getAvailableLocales().firstOrNull { candidate ->
        candidate.language.length == 2 &&
            runCatching { candidate.isO3Language.equals(language, ignoreCase = true) }
                .getOrDefault(false)
    } ?: locale
}
