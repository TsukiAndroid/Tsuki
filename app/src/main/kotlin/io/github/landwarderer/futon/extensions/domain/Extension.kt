package io.github.landwarderer.futon.extensions.domain

/**
 * Represents a user-installed multi-language extension.
 *
 * This data model is completely separate from [CustomSource] and the Mihon bridge.
 * It is persisted via [ExtensionRepository] (SharedPreferences + JSON).
 *
 * @param id          Stable UUID for this installation.
 * @param name        Human-readable name shown in the UI.
 * @param version     SemVer string, e.g. "1.2.3".
 * @param author      Extension author / publisher name.
 * @param description Short one-line description.
 * @param baseUrl     Root URL the extension targets, e.g. "https://mangadex.org".
 * @param language    BCP-47 language tag the extension primarily serves, e.g. "en".
 * @param iconUrl     Optional URL for a square icon image.
 * @param type        Runtime format – see [ExtensionType].
 * @param sourceCode  Raw source code for [ExtensionType.JS] and [ExtensionType.DART]
 *                    extensions; empty string for the other two types.
 * @param packageName APK package name for [ExtensionType.MIHON_APK]; empty otherwise.
 * @param templateName Name of the [ParserTemplate] for [ExtensionType.JSON_TEMPLATE]; empty otherwise.
 * @param isEnabled   Whether the extension appears in the Explore source list.
 * @param installedAt Unix-millis timestamp of installation.
 */
data class Extension(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val baseUrl: String,
    val language: String,
    val iconUrl: String,
    val type: ExtensionType,
    val sourceCode: String,
    val packageName: String,
    val templateName: String,
    val isEnabled: Boolean,
    val installedAt: Long,
)
