package io.github.landwarderer.futon.plugins.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.landwarderer.futon.plugins.domain.LoadedPlugin
import io.github.landwarderer.futon.plugins.domain.Plugin
import io.github.landwarderer.futon.plugins.domain.PluginMangaSource
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads JAR plugin files at runtime using Android's [dalvik.system.DexClassLoader].
 *
 * Compatible with the Usagi / UMA plugin ecosystem.
 *
 * Loading strategy (tries each in order until one succeeds):
 * 1. **Tsuki/UMA factory format** — the modern KSP-generated pattern used by UMA and
 *    modern Usagi forks. Looks for `tsuki.MangaParserFactoryKt` +
 *    `tsuki.model.MangaParserSource`, then falls back to the older
 *    `org.koitharu.kotatsu.parsers.*` package variants.
 * 2. **Legacy reflection format** — old-style plugins that expose a root object with
 *    a `getSources()` / `getParsers()` method. Reads `META-INF/plugin.json` for the
 *    entry class, or tries well-known conventional class names.
 * 3. Return null and log the failure gracefully — the app never crashes on a bad .jar.
 */
@Singleton
class PluginLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val loaderContext: MangaLoaderContext,
) {

    companion object {
        private const val TAG = "PluginLoader"

        // META-INF manifest entry name (legacy format)
        private const val PLUGIN_MANIFEST = "META-INF/plugin.json"

        // UMA / tsuki format class names (modern Usagi-compatible plugins)
        private const val TSUKI_FACTORY      = "tsuki.MangaParserFactoryKt"
        private const val TSUKI_SOURCE_ENUM  = "tsuki.model.MangaParserSource"
        private const val TSUKI_LOADER_CTX   = "tsuki.MangaLoaderContext"

        // Legacy org.koitharu.kotatsu.parsers.* variants
        private const val KOTATSU_FACTORY     = "org.koitharu.kotatsu.parsers.MangaParserFactoryKt"
        private const val KOTATSU_SOURCE_ENUM = "org.koitharu.kotatsu.parsers.model.MangaParserSource"
        private const val KOTATSU_LOADER_CTX  = "org.koitharu.kotatsu.parsers.MangaLoaderContext"

        // Well-known entry-point class names for the legacy format
        private val LEGACY_ENTRY_CLASSES = listOf(
            "uma.plugin.PluginFactory",
            "com.invalidd.uma.Plugin",
            "app.usagi.plugin.Plugin",
            "app.usagi.ext.Plugin",
            "com.github.usagi.plugin.Plugin",
        )

        // Reflection getter names for the legacy format
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

            // ── Strategy 1: tsuki / UMA factory format ──────────────────────────
            tryLoadTsukiFormat(classLoader, jarFile, existingMetadata)
                // ── Strategy 2: legacy reflection format ────────────────────────
                ?: tryLoadLegacyFormat(classLoader, jarFile, existingMetadata)
        }.getOrElse { e ->
            Log.e(TAG, "Failed to load plugin from ${jarFile.name}: ${e.message}", e)
            null
        }
    }

    // =========================================================================
    // Strategy 1 — tsuki / UMA factory format
    // =========================================================================

    /**
     * Loads a plugin that was built with the KSP-based factory pattern used by UMA
     * and modern Usagi-compatible plugins.
     *
     * Expected classes (tries `tsuki.*` first, then `org.koitharu.kotatsu.parsers.*`):
     * - `MangaParserFactoryKt`  — static factory with `newParser(source, ctx)` method
     * - `MangaParserSource`     — enum whose constants each represent one manga source
     * - `MangaLoaderContext`    — network/context interface passed to each parser
     */
    private fun tryLoadTsukiFormat(
        classLoader: ClassLoader,
        jarFile: File,
        existingMetadata: Plugin?,
    ): LoadedPlugin? {
        // Resolve the three key classes, preferring the tsuki.* package
        val (factoryClass, enumClass, ctxClass) =
            loadTsukiClasses(classLoader) ?: return null

        // Resolve the static newParser(source, ctx) method
        val newParserMethod: Method = runCatching {
            factoryClass.getMethod("newParser", enumClass, ctxClass)
        }.getOrNull() ?: run {
            Log.w(TAG, "${jarFile.name}: found factory class but no newParser method")
            return null
        }

        // Create a proxy so the host's MangaLoaderContext appears as ctxClass to the plugin
        val ctxProxy: Any? = if (ctxClass.isInterface) {
            createLoaderContextProxy(ctxClass)
        } else {
            null
        }

        // Enumerate all sources declared by the plugin
        val enumConstants = enumClass.enumConstants ?: run {
            Log.w(TAG, "${jarFile.name}: MangaParserSource has no enum constants")
            return null
        }

        val pluginId = (existingMetadata?.id ?: jarFile.nameWithoutExtension)
            .replace(Regex("[^A-Za-z0-9_-]"), "_")

        // Read plugin-level metadata from manifest or fallback to jar name
        val manifest    = readManifest(jarFile)
        val pluginName  = manifest?.get("name") as? String
            ?: existingMetadata?.name
            ?: jarFile.nameWithoutExtension
        val pluginVer   = manifest?.get("version") as? String
            ?: existingMetadata?.version
            ?: "unknown"
        val pluginAuth  = manifest?.get("author") as? String
            ?: existingMetadata?.author
            ?: "Unknown"
        val pluginDesc  = manifest?.get("description") as? String
            ?: existingMetadata?.description
            ?: ""

        val sources = enumConstants.mapNotNull { c ->
            val enumName = (c as? Enum<*>)?.name ?: return@mapNotNull null
            val title = readEnumTitle(c) ?: enumName
            val locale = readEnumLocale(c) ?: ""
            PluginMangaSource(
                pluginId          = pluginId,
                sourceName        = enumName,
                displayName       = title,
                pluginDisplayName = pluginName,
                locale            = locale,
            )
        }

        Log.d(TAG, "${jarFile.name}: loaded tsuki-format plugin with ${sources.size} sources")

        val metadata = existingMetadata?.copy(
            name        = pluginName,
            version     = pluginVer,
            author      = pluginAuth,
            description = pluginDesc,
            sourceCount = sources.size,
        ) ?: Plugin(
            id          = pluginId,
            name        = pluginName,
            version     = pluginVer,
            author      = pluginAuth,
            description = pluginDesc,
            jarPath     = jarFile.absolutePath,
            githubRepo  = null,
            isEnabled   = true,
            installedAt = System.currentTimeMillis(),
            lastUpdated = System.currentTimeMillis(),
            sourceCount = sources.size,
        )

        return LoadedPlugin(
            metadata          = metadata,
            sources           = sources,
            pluginInstance    = null,     // tsuki-format uses factory, not an instance
            classLoader       = classLoader,
            newParserMethod   = newParserMethod,
            sourceEnumClass   = enumClass,
            loaderContextProxy = ctxProxy,
        )
    }

    /**
     * Tries to load the three essential classes for the tsuki-format plugin,
     * first with the `tsuki.*` package (UMA / modern Usagi) and then with the
     * legacy `org.koitharu.kotatsu.parsers.*` package names.
     *
     * Returns a Triple of (factory, enum, context) or null if not found.
     */
    private fun loadTsukiClasses(
        classLoader: ClassLoader,
    ): Triple<Class<*>, Class<*>, Class<*>>? {
        // Try modern tsuki.* package
        runCatching {
            Triple(
                classLoader.loadClass(TSUKI_FACTORY),
                classLoader.loadClass(TSUKI_SOURCE_ENUM),
                classLoader.loadClass(TSUKI_LOADER_CTX),
            )
        }.getOrNull()?.let { return it }

        // Fall back to org.koitharu.kotatsu.parsers.* (older plugins)
        runCatching {
            Triple(
                classLoader.loadClass(KOTATSU_FACTORY),
                classLoader.loadClass(KOTATSU_SOURCE_ENUM),
                classLoader.loadClass(KOTATSU_LOADER_CTX),
            )
        }.getOrNull()?.let { return it }

        return null
    }

    /**
     * Creates a [java.lang.reflect.Proxy] that implements [ctxInterface] (the plugin's
     * `MangaLoaderContext` interface) and forwards all method calls to the host's
     * [loaderContext] by matching on method name and parameter count.
     *
     * This bridge is needed because the plugin classes and host classes are loaded by
     * different ClassLoaders, so the plugin's `tsuki.MangaLoaderContext` and the host's
     * `org.koitharu.kotatsu.parsers.MangaLoaderContext` are treated as unrelated types by the JVM.
     */
    private fun createLoaderContextProxy(ctxInterface: Class<*>): Any? =
        runCatching {
            Proxy.newProxyInstance(
                ctxInterface.classLoader,
                arrayOf(ctxInterface),
            ) { _, method, args ->
                val hostMethods = loaderContext.javaClass.methods
                    .filter { it.name == method.name }
                val argCount = args?.size ?: 0
                val hostMethod = hostMethods.firstOrNull { it.parameterCount == argCount }
                    ?: hostMethods.firstOrNull()
                    ?: return@newProxyInstance null
                runCatching {
                    hostMethod.invoke(loaderContext, *(args ?: emptyArray()))
                }.getOrNull()
            }
        }.getOrNull()

    /** Reads the `title` (display name) property from a MangaParserSource enum constant. */
    private fun readEnumTitle(c: Any): String? =
        runCatching { c.javaClass.getMethod("getTitle").invoke(c) as? String }.getOrNull()
            ?: runCatching { c.javaClass.getMethod("title").invoke(c) as? String }.getOrNull()
            ?: runCatching { c.javaClass.getField("title").get(c) as? String }.getOrNull()

    /** Reads the `locale` property from a MangaParserSource enum constant. */
    private fun readEnumLocale(c: Any): String? =
        runCatching { c.javaClass.getMethod("getLocale").invoke(c) as? String }.getOrNull()
            ?: runCatching { c.javaClass.getMethod("locale").invoke(c) as? String }.getOrNull()
            ?: runCatching { c.javaClass.getField("locale").get(c) as? String }.getOrNull()

    // =========================================================================
    // Strategy 2 — legacy reflection format
    // =========================================================================

    private fun tryLoadLegacyFormat(
        classLoader: ClassLoader,
        jarFile: File,
        existingMetadata: Plugin?,
    ): LoadedPlugin? {
        val manifest = readManifest(jarFile)
        val entryClassName = manifest?.get("entryClass") as? String
            ?: findLegacyEntryClass(classLoader)

        if (entryClassName == null) {
            Log.w(TAG, "No plugin entry class found in ${jarFile.name}")
            return null
        }

        val pluginClass    = classLoader.loadClass(entryClassName)
        val pluginInstance = tryInstantiateLegacy(pluginClass, classLoader)

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
                pluginId          = pluginId,
                sourceName        = srcName.replace(Regex("[^A-Za-z0-9_-]"), "_"),
                displayName       = srcDisplay,
                pluginDisplayName = pluginName,
                locale            = locale,
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

        Log.d(TAG, "${jarFile.name}: loaded legacy-format plugin with ${sources.size} sources")

        return LoadedPlugin(
            metadata       = metadata,
            sources        = sources,
            pluginInstance = pluginInstance,
            classLoader    = classLoader,
        )
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

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

    /** Tries each legacy entry-class name until one resolves successfully. */
    private fun findLegacyEntryClass(classLoader: ClassLoader): String? {
        for (className in LEGACY_ENTRY_CLASSES) {
            runCatching { classLoader.loadClass(className) }.getOrNull()
                ?.let { return className }
        }
        return null
    }

    /**
     * Tries to instantiate the plugin class for the legacy format.
     * Tries a no-arg constructor first, then a single-arg constructor with MangaLoaderContext.
     */
    private fun tryInstantiateLegacy(cls: Class<*>, classLoader: ClassLoader): Any? {
        // Try no-arg constructor
        runCatching { cls.getDeclaredConstructor().newInstance() }
            .onSuccess { return it }

        // Try with host's MangaLoaderContext type (org.koitharu.kotatsu.parsers.*)
        runCatching {
            cls.getDeclaredConstructor(MangaLoaderContext::class.java).newInstance(loaderContext)
        }.onSuccess { return it }

        // Try loading the context class from the plugin's own classloader (legacy kotatsu path)
        runCatching {
            val loaderClass = classLoader.loadClass(KOTATSU_LOADER_CTX)
            cls.getDeclaredConstructor(loaderClass).newInstance(loaderContext)
        }.onSuccess { return it }

        return null
    }

    /** Reads a String property from a plugin instance via reflection. */
    private fun readStringProp(instance: Any?, names: List<String>): String? {
        instance ?: return null
        for (name in names) {
            runCatching {
                instance.javaClass.getMethod(name).invoke(instance) as? String
            }.getOrNull()?.let { return it }
            runCatching {
                instance.javaClass.getField(name).get(instance) as? String
            }.getOrNull()?.let { return it }
        }
        return null
    }

    /**
     * Calls the plugin's source-getter method and converts the result to a
     * list of (technicalName, displayName, locale) triples.
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
