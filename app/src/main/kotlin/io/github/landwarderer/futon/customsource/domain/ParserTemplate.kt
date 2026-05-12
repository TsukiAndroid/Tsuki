package io.github.landwarderer.futon.customsource.domain

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents a user-imported parser template.
 *
 * A template defines generic scraping rules for an entire family of manga
 * websites (e.g. "Madara", "MangaDex-style"). Any site that matches a
 * template family can be added as a custom source backed by that template,
 * without waiting for an app update.
 *
 * The full original JSON is stored in [rawJson] so it can be re-parsed by
 * the runtime parser engine without any information loss.
 */
@Parcelize
data class ParserTemplate(
    val id: Long,
    val name: String,
    val version: String,
    /** The template's declared content type, e.g. "html" or "json". */
    val type: String,
    /** The complete raw JSON string as the user imported it. */
    val rawJson: String,
    val importedAt: Long = System.currentTimeMillis(),
) : Parcelable
