package io.github.landwarderer.futon.core.db

import android.content.res.Resources
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.landwarderer.futon.R
import org.koitharu.kotatsu.parsers.model.SortOrder

class DatabasePrePopulateCallback(private val resources: Resources) : RoomDatabase.Callback() {

	override fun onCreate(db: SupportSQLiteDatabase) {
		db.execSQL(
			"INSERT INTO favourite_categories (created_at, sort_key, title, `order`, track, show_in_lib, `deleted_at`) VALUES (?,?,?,?,?,?,?)",
			arrayOf(
				System.currentTimeMillis(),
				1,
				resources.getString(R.string.read_later),
				SortOrder.NEWEST.name,
				1,
				1,
				0L,
			)
		)

		val now = System.currentTimeMillis()
		db.execSQL(
			"INSERT INTO external_extension_repos (type, baseUrl, name, shortName, website, signingKeyFingerprint, createdAt, updatedAt, lastSuccessAt) VALUES (?,?,?,?,?,?,?,?,?)",
			arrayOf(
				"MIHON",
				"https://raw.githubusercontent.com/keiyoushi/extensions/main",
				"Keiyoushi",
				"Keiyoushi",
				"https://keiyoushi.github.io/extensions",
				"9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2",
				now,
				now,
				now,
			)
		)
	}
}
