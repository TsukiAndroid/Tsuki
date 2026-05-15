package io.github.landwarderer.futon.extensions.di

import android.content.Context
import android.content.SharedPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * Hilt module for the multi-language extension system.
 *
 * Provides a named [SharedPreferences] instance so that extension data is
 * stored in a dedicated file separate from app settings and custom source prefs.
 */
@Module
@InstallIn(SingletonComponent::class)
object ExtensionModule {

    @Provides
    @Singleton
    @Named("extensions_prefs")
    fun provideExtensionPrefs(
        @ApplicationContext context: Context,
    ): SharedPreferences =
        context.getSharedPreferences("futon_extensions", Context.MODE_PRIVATE)
}
