package io.github.landwarderer.futon.core.parser

import org.koitharu.kotatsu.parsers.DirectParsersAccess
import org.koitharu.kotatsu.parsers.ParsersBundle
import org.koitharu.kotatsu.parsers.core.ParsersBundleImpl

object AppParsers {

	@OptIn(DirectParsersAccess::class)
	@Volatile
	var bundle: ParsersBundle = ParsersBundleImpl
}
