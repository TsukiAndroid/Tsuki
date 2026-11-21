package io.github.landwarderer.futon.core.parser

import org.koitharu.kotatsu.parsers.DirectParsersAccess
import org.koitharu.kotatsu.parsers.ParsersBundle
import org.koitharu.kotatsu.parsers.core.ParsersBundleImpl

/**
 * Single place where the app holds the parsers it is currently using.
 * Everything else must go through [bundle] (or MangaLoaderContext.parsers),
 * never through the copy the library was compiled with.
 */
object AppParsers {

    @Volatile
    @OptIn(DirectParsersAccess::class)
    var bundle: ParsersBundle = ParsersBundleImpl
}
