package io.github.landwarderer.futon.core.parser

import android.content.Context
import androidx.annotation.AnyThread
import androidx.collection.ArrayMap
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.landwarderer.futon.core.cache.MemoryContentCache
import io.github.landwarderer.futon.core.model.LocalMangaSource
import io.github.landwarderer.futon.core.model.MangaSourceInfo
import io.github.landwarderer.futon.core.model.TestMangaSource
import io.github.landwarderer.futon.core.model.UnknownMangaSource
import io.github.landwarderer.futon.core.parser.external.ExternalMangaRepository
import io.github.landwarderer.futon.core.parser.external.ExternalMangaSource
import io.github.landwarderer.futon.customsource.data.CustomMangaRepository
import io.github.landwarderer.futon.customsource.data.CustomSourcesRepository
import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
import io.github.landwarderer.futon.customsource.domain.CustomSourceType
import io.github.landwarderer.futon.local.data.LocalMangaRepository
import io.github.landwarderer.futon.mihon.MihonExtensionManager
import io.github.landwarderer.futon.mihon.MihonMangaRepository
import io.github.landwarderer.futon.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

interface MangaRepository {

        val source: MangaSource

        val sortOrders: Set<SortOrder>

        var defaultSortOrder: SortOrder

        val filterCapabilities: MangaListFilterCapabilities

        suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga>

        suspend fun getDetails(manga: Manga): Manga

        suspend fun getPages(chapter: MangaChapter): List<MangaPage>

        suspend fun getPageUrl(page: MangaPage): String

        suspend fun getFilterOptions(): MangaListFilterOptions

        suspend fun getRelated(seed: Manga): List<Manga>

        suspend fun find(manga: Manga): Manga? {
                val list = getList(0, SortOrder.RELEVANCE, MangaListFilter(query = manga.title))
                return list.find { x -> x.id == manga.id }
        }

        @Singleton
        class Factory @Inject constructor(
                @ApplicationContext private val context: Context,
                private val localMangaRepository: LocalMangaRepository,
                private val loaderContext: MangaLoaderContext,
                private val contentCache: MemoryContentCache,
                private val mirrorSwitcher: MirrorSwitcher,
                private val mihonExtensionManager: MihonExtensionManager,
                @Suppress("unused") customSourcesRepository: CustomSourcesRepository,
        ) {

                private val cache = ArrayMap<MangaSource, WeakReference<MangaRepository>>()

                @AnyThread
                fun create(source: MangaSource): MangaRepository {
                        when (source) {
                                is MangaSourceInfo -> return create(source.mangaSource)
                                LocalMangaSource -> return localMangaRepository
                                UnknownMangaSource -> return EmptyMangaRepository(source)
                        }
                        cache[source]?.get()?.let { return it }
                        return synchronized(cache) {
                                cache[source]?.get()?.let { return it }
                                val repository = createRepository(source)
                                if (repository != null) {
                                        cache[source] = WeakReference(repository)
                                        repository
                                } else {
                                        EmptyMangaRepository(source)
                                }
                        }
                }

                private fun createRepository(source: MangaSource): MangaRepository? {
                return when (source) {
                        is MangaParserSource -> ParserMangaRepository(
                                parser = loaderContext.newParserInstance(source),
                                cache = contentCache,
                                mirrorSwitcher = mirrorSwitcher,
                        )

                        TestMangaSource -> TestMangaRepository(
                                loaderContext = loaderContext,
                                cache = contentCache,
                        )

                        is ExternalMangaSource -> if (source.isAvailable(context)) {
                                ExternalMangaRepository(
                                        contentResolver = context.contentResolver,
                                        source = source,
                                        cache = contentCache,
                                )
                        } else {
                                EmptyMangaRepository(source)
                        }

                        is MihonMangaSource -> MihonMangaRepository(
                                source = source,
                                cache = contentCache,
                        )

                        is CustomMangaSource -> {
                                // If the source was auto-matched to a Kotatsu parser, route it to
                                // ParserMangaRepository for full inbuilt-source quality.
                                if (source.source.type == CustomSourceType.KOTATSU_PARSER) {
                                        val parserName = source.source.parserSourceName
                                        val parserSource = parserName?.let { n ->
                                                MangaParserSource.entries.find { it.name == n }
                                        }
                                        if (parserSource != null) {
                                                // When the user's URL is on a different domain than
                                                // the template parser's hardcoded default (fingerprint
                                                // match — e.g. a mirror / totally new site), wrap the
                                                // loader context so the parser talks to the user's
                                                // domain instead.
                                                val templateParser = loaderContext.newParserInstance(parserSource)
                                                val customHost = runCatching {
                                                        java.net.URI(source.source.baseUrl)
                                                                .host?.lowercase()?.removePrefix("www.")
                                                }.getOrNull()
                                                val parserHost = templateParser.domain
                                                        .lowercase().removePrefix("www.")

                                                val finalParser = if (
                                                        customHost != null && customHost != parserHost
                                                ) {
                                                        DomainOverrideLoaderContext(
                                                                delegate = loaderContext,
                                                                templateSource = parserSource,
                                                                customDomain = customHost,
                                                        ).newParserInstance(parserSource)
                                                } else {
                                                        templateParser
                                                }

                                                return ParserMangaRepository(
                                                        parser = finalParser,
                                                        cache = contentCache,
                                                        mirrorSwitcher = mirrorSwitcher,
                                                )
                                        }
                                }
                                CustomMangaRepository(customSource = source)
                        }

                        else -> {
                                if (source.name.startsWith("mihon:") || source.name.startsWith("MIHON_")) {
                                        mihonExtensionManager.getMihonMangaSourceByName(source.name)?.let {
                                                return MihonMangaRepository(
                                                        source = it,
                                                        cache = contentCache,
                                                )
                                        }
                                }
                                null
                        }
                }
                }
        }
}
