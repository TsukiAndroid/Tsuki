package io.github.landwarderer.futon.webviewsource.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.landwarderer.futon.core.db.MangaDatabase
import io.github.landwarderer.futon.core.db.dao.WebViewSourceDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WebViewSourceModule {

    @Provides
    @Singleton
    fun provideWebViewSourceDao(db: MangaDatabase): WebViewSourceDao =
        db.webViewSourceDao()
}
