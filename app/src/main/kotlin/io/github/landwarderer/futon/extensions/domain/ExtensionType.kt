package io.github.landwarderer.futon.extensions.domain

/**
 * Identifies the runtime/format of a user-installed extension.
 *
 * JS           – QuickJS-powered JavaScript extension (quickjs-kt already in deps).
 * DART         – D4rt-powered Dart extension (d4rt dependency added).
 * MIHON_APK    – Bridges the existing Mihon/Tachiyomi APK system.
 * JSON_TEMPLATE – Wraps an existing [ParserTemplate] from the TemplateHtmlParser system.
 *
 * IMPORTANT: this is entirely separate from Custom Sources and KotatsuParserMatcher.
 */
enum class ExtensionType {
    JS,
    DART,
    MIHON_APK,
    JSON_TEMPLATE,
}
