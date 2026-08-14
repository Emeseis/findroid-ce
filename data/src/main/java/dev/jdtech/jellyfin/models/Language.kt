package dev.jdtech.jellyfin.models

import java.text.Normalizer
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
    val titleWithoutForcedMarker = if (isForced) {
        title
            ?.split(Regex("\\s+(?:—|-)\\s+"))
            ?.filterNot { component ->
                isForcedMarker(component, forcedLabel)
            }
            ?.joinToString(" - ")
    } else {
        title
    }

    val base = compactTrackDisplayName(languageTag, titleWithoutForcedMarker)
    return if (isForced && base != null) "$base ($forcedLabel)" else base
}

/**
 * Matches Jellyfin's universal marker and localized grammatical variants without
 * hard-coding a particular language (for example, masculine/feminine suffixes).
 */
private fun isForcedMarker(component: String, forcedLabel: String): Boolean {
    val marker = normalizeMarker(component)
    val localizedMarker = normalizeMarker(forcedLabel)
    if (marker == "forced" || marker == localizedMarker) return true
    if (marker.length < 4 || localizedMarker.length < 4) return false

    val commonPrefixLength = marker.zip(localizedMarker)
        .takeWhile { (first, second) -> first == second }
        .size
    val shortestLength = minOf(marker.length, localizedMarker.length)
    val maximumSuffixVariation = maxOf(1, shortestLength / 3)

    return commonPrefixLength >= shortestLength - maximumSuffixVariation &&
        kotlin.math.abs(marker.length - localizedMarker.length) <= maximumSuffixVariation
}

private fun normalizeMarker(value: String): String = Normalizer
    .normalize(value.trim().trim('(', ')', '[', ']'), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
    .lowercase(Locale.ROOT)

// Cached mapping from ISO 639-2/T (three-letter) language codes to the two-letter Locale.
// Locale.getAvailableLocales() scans ~800 entries; computing it once avoids O(n) work per
// track lookup inside languageLocale().
private val iso3ToLocale: Map<String, Locale> by lazy {
    Locale.getAvailableLocales()
        .filter { it.language.length == 2 }
        .mapNotNull { locale ->
            runCatching { locale.isO3Language.lowercase(Locale.ROOT) to locale }.getOrNull()
        }
        .toMap()
}

private fun languageLocale(languageTag: String): Locale {
    val locale = Locale.forLanguageTag(languageTag)
    val language = languageTag.substringBefore('-')
    if (language.length != 3) return locale
    return iso3ToLocale[language.lowercase(Locale.ROOT)] ?: locale
}
