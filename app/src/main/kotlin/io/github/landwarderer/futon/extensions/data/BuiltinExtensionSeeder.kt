package io.github.landwarderer.futon.extensions.data

  import android.content.Context
  import android.content.SharedPreferences
  import dagger.hilt.android.qualifiers.ApplicationContext
  import io.github.landwarderer.futon.BuildConfig
  import io.github.landwarderer.futon.extensions.domain.Extension
  import io.github.landwarderer.futon.extensions.domain.ExtensionType
  import java.util.UUID
  import javax.inject.Inject
  import javax.inject.Named
  import javax.inject.Singleton

  /**
   * Seeds built-in JS extensions from assets/extensions/ into [ExtensionRepository]
   * and keeps them in sync across APK updates.
   *
   * On every versionCode bump, every .js file under assets/extensions/ is read and
   * either inserted or updated in the repository. This ensures bug-fixes to bundled
   * extensions (e.g. manhwaread.js) take effect automatically after installing a new
   * APK, with no need to clear app data.
   *
   * Skips re-seeding when [BuildConfig.VERSION_CODE] has not changed since the last
   * run, so overhead on ordinary cold-starts is negligible.
   */
  @Singleton
  class BuiltinExtensionSeeder @Inject constructor(
      @ApplicationContext private val context: Context,
      private val extensionRepository: ExtensionRepository,
      @Named("extensions_prefs") private val prefs: SharedPreferences,
  ) {
      fun seedIfNeeded() {
          val lastSeeded = prefs.getLong(KEY_SEEDED_VERSION, -1L)
          val current = BuildConfig.VERSION_CODE.toLong()
          if (lastSeeded == current) return

          val assetManager = context.assets
          val files = runCatching { assetManager.list("extensions") ?: emptyArray<String>() }
              .getOrDefault(emptyArray())
              .filter { it.endsWith(".js") }

          for (fileName in files) {
              val source = runCatching {
                  assetManager.open("extensions/$fileName").bufferedReader().use { it.readText() }
              }.getOrNull() ?: continue

              val name = fileNameToDisplayName(fileName)
              val baseUrl = inferBaseUrl(fileName, source)

              val existing = extensionRepository.getAll().firstOrNull {
                  it.name.equals(name, ignoreCase = true) && it.type == ExtensionType.JS
              }
              if (existing != null) {
                  extensionRepository.save(existing.copy(sourceCode = source))
              } else {
                  extensionRepository.save(
                      Extension(
                          id = UUID.nameUUIDFromBytes(fileName.toByteArray()).toString(),
                          name = name,
                          version = "1.0.0",
                          author = "Built-in",
                          description = "Bundled extension",
                          baseUrl = baseUrl,
                          language = "en",
                          iconUrl = "",
                          type = ExtensionType.JS,
                          sourceCode = source,
                          packageName = "",
                          templateName = "",
                          isEnabled = true,
                          installedAt = System.currentTimeMillis(),
                      ),
                  )
              }
          }
          prefs.edit().putLong(KEY_SEEDED_VERSION, current).apply()
      }

      private fun fileNameToDisplayName(fileName: String): String =
          fileName.removeSuffix(".js")
              .split(Regex("[_\\-]"))
              .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

      private fun inferBaseUrl(fileName: String, source: String): String {
          Regex("""(?:var|const|let)\s+BASE_URL\s*=\s*['"]([^'"]+)['"]""")
              .find(source)?.groupValues?.getOrNull(1)?.let { return it }
          return when {
              "manhwa" in fileName.lowercase() -> "https://manhwaread.com"
              else -> "https://example.com"
          }
      }

      companion object {
          private const val KEY_SEEDED_VERSION = "builtin_seeded_version"
      }
  }
  