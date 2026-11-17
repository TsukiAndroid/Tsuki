package io.github.landwarderer.futon.core.db.migrations

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.landwarderer.futon.core.parser.AppParsers

class Migration16To17(context: Context) : Migration(16, 17) {

	private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

	override fun migrate(db: SupportSQLiteDatabase) {
		db.execSQL("CREATE TABLE `sources` (`source` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `sort_key` INTEGER NOT NULL, PRIMARY KEY(`source`))")
		db.execSQL("CREATE INDEX `index_sources_sort_key` ON `sources` (`sort_key`)")
		val hiddenSources = prefs.getStringSet("sources_hidden", null).orEmpty()
		val order = prefs.getString("sources_order_2", null)?.split('|').orEmpty()
		val sources = AppParsers.bundle.sources
		for (source in sources) {
			val name = source.name
			val isHidden = name in hiddenSources
			var sortKey = order.indexOf(name)
			if (sortKey == -1) {
				if (isHidden) {
					// TODO(parsers-migration): ordinal — ParserMangaSource is no longer an enum;
					// .ordinal was used to assign a stable sort key for hidden sources.
					// The new type has no ordinal. Using the source's index in the list as a fallback.
					sortKey = order.size + sources.indexOf(source)
				} else {
					continue
				}
			}
			db.execSQL(
				"INSERT INTO `sources` (`source`, `enabled`, `sort_key`) VALUES (?, ?, ?)",
				arrayOf(name, (!isHidden).toInt(), sortKey),
			)
		}
	}

	private fun Boolean.toInt() = if (this) 1 else 0
}
