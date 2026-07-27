package io.github.landwarderer.futon.plugins.domain

import kotlinx.serialization.Serializable

/**
 * Represents an installed JAR plugin compatible with the Usagi/UMA plugin ecosystem.
 *
 * Plugins are .jar files loaded at runtime via DexClassLoader. Each plugin can
 * expose one or more manga sources that appear in the Explore tab alongside
 * built-in and custom sources.
 */
@Serializable
data class Plugin(
    val id: String,              // unique identifier (typically derived from jar filename)
    val name: String,            // display name from plugin manifest
    val version: String,         // version string (e.g. "1.0.0" or tag name)
    val author: String,          // author name from plugin manifest
    val description: String,     // short description
    val jarPath: String,         // absolute path to .jar file in app's private storage
    val githubRepo: String?,     // "owner/repo" or full URL for auto-update checks
    val isEnabled: Boolean,      // whether this plugin is active
    val installedAt: Long,       // System.currentTimeMillis() at install time
    val lastUpdated: Long,       // timestamp of last update check
    val sourceCount: Int,        // number of manga sources provided by this plugin
)
