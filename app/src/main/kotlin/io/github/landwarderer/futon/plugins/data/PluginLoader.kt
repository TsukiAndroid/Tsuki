package io.github.landwarderer.futon.plugins.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.landwarderer.futon.plugins.domain.LoadedPlugin
import io.github.landwarderer.futon.plugins.domain.Plugin
import io.github.landwarderer.futon.plugins.domain.PluginMangaSource
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads JAR plugin files at runtime using Android's [dalvik.system.DexClassLoader].
 *
 * Compatible with the Usagi / UMA plugin ecosystem (com.github.UsagiApp:core-exts).
 *
 * Loading strategy (tries each in order until one succeeds):
 * 1. Read `META-INF/plugin.json` from the ZIP/JAR to find the main class name.
 * 2. Try well-known conventional entry-point class names from the Usagi ecosystem.
 * 3. Return null and log the failure gracefully — the app never crashes on a bad .jar.
 *
 * When a plugin class is found, it is expected to implement core-exts' plugin interface.
 * Metadata (name, version, author) is also read from `META-INF/plugin.json` when available;
 * otherwise sensible defaults derived from the filename are used.
 */
@Singleton
class PluginLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val loaderContext: MangaLoaderContext,
) {

    companion object {
        private const val TAG = "PluginLoader"

        // META-INF manifest entry name
        private const val PLUGIN_MANIFEST = "META-INF/plugin.json"

        // Well-known entry-point class names used by Usagi/UMA ecosystem
        private val KNOWN_ENTRY_CLASSES = listOf(
            "uma.plugin.PluginFactory",
            "com.invalidd.uma.Plugin",
            "app.usagi.plugin.Plugin",
            "app.usagi.ext.Plugin",
            "com.github.usagi.plugin.Plugin",
        )

        // Methods the plugin entry class should expose (tried via reflection)
        private val SOURCE_GETTER_NAMES = listOf("getSources", "getParsers", "sources", "parsers")
        private val NAME_GETTER_NAMES   = listOf("getName", "name", "getPluginName")
        private val VER_GETTER_NAMES    = listOf("getVersion", "version", "getPluginVersion")
        private val AUTH_GETTER_NAMES   = listOf("getAuthor", "author")
        private val DESC_GETTER_NAMES   = listOf("getDescription", "description")
    }

    /**
     * Attempts to load a plugin from [jarFile].
     *
     * @return A [LoadedPlugin] on success, null on any failure.
     */
    fun loadPlugin(jarFile: File, existingMetadata: Plugin? = null): LoadedPlugin? {
        if (!jarFile.exists() || !jarFile.canRead()) {
            Log.w(TAG, "JAR not readable: ${jarFile.absolutePath}")
            return null
        }
        return runCatching {
            val dexOutputDir = context.codeCacheDir
            val classLoader = dalvik.system.DexClassLoader(
                jarFile.absolutePath,
                dexOutputDir.absolutePath,
                null,
                context.classLoader,
            )

            val manifest = readManifest(jarFile)
            val entryClassName = manifest?.get("entryClass") as? String
                ?: findEntryClass(classLoader)

            if (entryClassName == null) {
                Log.w(TAG, "No plugin entry class found in ${jarFile.name}")
                return@runCatching null
            }

            val pluginClass = classLoader.loadClass(entryClassName)
            val pluginInstance = tryInstantiate(pluginClass, classLoader)

            val pluginName    = readStringProp(pluginInstance, NAME_GETTER_NAMES)
                ?: manifest?.get("name") as? String
                ?: existingMetadata?.name
                ?: jarFile.nameWithoutExtension
            val pluginVersion = readStringProp(pluginInstance, VER_GETTER_NAMES)
                ?: manifest?.get("version") as? String
                ?: existingMetadata?.version
                ?: "unknown"
            val pluginAuthor  = readStringProp(pluginInstance, AUTH_GETTER_NAMES)
                ?: manifest?.get("author") as? String
                ?: existingMetadata?.author
                ?: "Unknown"
            val pluginDesc    = readStringProp(pluginInstance, DESC_GETTER_NAMES)
                ?: manifest?.get("description") as? String
                ?: existingMetadata?.description
                ?: ""

            val rawSources = readSourceList(pluginInstance, loaderContext)
            val pluginId   = (existingMetadata?.id ?: jarFile.nameWithoutExtension)
                .replace(Regex("[^A-Za-z0-9_-]"), "_")

            val sources = rawSources.map { (srcName, srcDisplay, locale) ->
                PluginMangaSource(
                    pluginId       = pluginId,
                    sourceName     = srcName.replace(Regex("[^A-Za-z0-9_-]"), "_"),
                    displayName    = srcDisplay,
                    pluginDisplayName = pluginName,
                    locale         = locale,
                )
            }

            val metadata = existingMetadata?.copy(
                name        = pluginName,
                version     = pluginVersion,
                author      = pluginAuthor,
                description = pluginDesc,
                sourceCount = sources.size,
            ) ?: Plugin(
                id          = pluginId,
                name        = pluginName,
                version     = pluginVersion,
                author      = pluginAuthor,
                description = pluginDesc,
                jarPath     = jarFile.absolutePath,
                githubRepo  = null,
                isEnabled   = true,
                installedAt = System.currentTimeMillis(),
                lastUpdated = System.currentTimeMillis(),
                sourceCount = sources.size,
            )

            LoadedPlugin(
                metadata       = metadata,
                sources        = sources,
                pluginInstance = pluginInstance,
                classLoader    = classLoader,
            )
        }.getOrElse { e ->
            Log.e(TAG, "Failed to load plugin from ${jarFile.name}: ${e.message}", e)
            null
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Reads META-INF/plugin.json from the JAR as a simple map. */
    private fun readManifest(jarFile: File): Map<*, *>? = runCatching {
        ZipFile(jarFile).use { zip ->
            val entry = zip.getEntry(PLUGIN_MANIFEST) ?: return@runCatching null
            val json  = zip.getInputStream(entry).bufferedReader().readText()
            // Minimal JSON parse: extract top-level string key/values
            val result = mutableMapOf<String, String>()
            val pattern = Regex(""""(\w+)"\s*:\s*"([^"]+)"""")
            pattern.findAll(json).forEach { m ->
                result[m.groupValues[1]] = m.groupValues[2]
            }
            result
        }
    }.getOrNull()

    /** Tries each known entry-class name until one resolves successfully. */
    private fun findEntryClass(classLoader: ClassLoader): String? {
        for (className in KNOWN_ENTRY_CLASSES) {
            runCatching { classLoader.loadClass(className) }.getOrNull()
                ?.let { return className }
        }
        return null
    }

    /** Tries to instantiate the plugin class (no-arg constructor first, then with MangaLoaderContext). */
    private fun tryInstantiate(cls: Class<*>, classLoader: ClassLoader): Any? {
        // Try no-arg constructor
        runCatching { cls.getDeclaredConstructor().newInstance() }
            .onSuccess { return it }

        // Try single-arg constructor with MangaLoaderContext
        runCatching {
            val loaderClass = classLoader.loadClass("org.koitharu.kotatsu.parsers.MangaLoaderContext")
            cls.getDeclaredConstructor(loaderClass).newInstance(loaderContext)
        }.onSuccess { return it }

        return null
    }

    /** Reads a String property from a plugin instance via reflection. */
    private fun readStringProp(instance: Any?, names: List<String>): String? {
        instance ?: return null
        for (name in names) {
            runCatching {
                // Try as method
                instance.javaClass.getMethod(name).invoke(instance) as? String
            }.getOrNull()?.let { return it }
            runCatching {
                // Try as field
                instance.javaClass.getField(name).get(instance) as? String
            }.getOrNull()?.let { return it }
        }
        return null
    }

    /**
     * Calls the plugin's source-getter method and converts the result to a
     * list of (technicalName, displayName, locale) triples.
     *
     * Handles: List<MangaParser>, List<Any>, and single-source plugins.
     */
    @Suppress("UNCHECKED_CAST")
    private fun readSourceList(
        instance: Any?,
        loaderContext: MangaLoaderContext,
    ): List<Triple<String, String, String>> {
        instance ?: return emptyList()

        for (getterName in SOURCE_GETTER_NAMES) {
            val raw = runCatching {
                instance.javaClass.getMethod(getterName).invoke(instance)
            }.getOrNull() ?: continue

            when (raw) {
                is List<*> -> {
                    if (raw.isEmpty()) return emptyList()
                    return raw.mapNotNull { src ->
                        src ?: return@mapNotNull null
                        val name    = reflectSourceName(src)    ?: src.javaClass.simpleName
                        val display = reflectSourceDisplay(src) ?: name
                        val locale  = reflectSourceLocale(src)  ?: ""
                        Triple(name, display, locale)
                    }
                }
                // Plugin is itself a single source
                else -> {
                    val name    = reflectSourceName(raw)    ?: raw.javaClass.simpleName
                    val display = reflectSourceDisplay(raw) ?: name
                    val locale  = reflectSourceLocale(raw)  ?: ""
                    return listOf(Triple(name, display, locale))
                }
            }
        }
        return emptyList()
    }

    private fun reflectSourceName(src: Any): String? =
        runCatching { src.javaClass.getMethod("name").invoke(src) as? String }.getOrNull()
            ?: runCatching { src.javaClass.getField("name").get(src) as? String }.getOrNull()

    private fun reflectSourceDisplay(src: Any): String? =
        runCatching { src.javaClass.getMethod("getTitle").invoke(src) as? String }.getOrNull()
            ?: runCatching { src.javaClass.getMethod("title").invoke(src) as? String }.getOrNull()
            ?: reflectSourceName(src)

    private fun reflectSourceLocale(src: Any): String? =
        runCatching { src.javaClass.getMethod("locale").invoke(src) as? String }.getOrNull()
            ?: runCatching { src.javaClass.getField("locale").get(src) as? String }.getOrNull()
}
