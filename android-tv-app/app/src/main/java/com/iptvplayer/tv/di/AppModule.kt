package com.iptvplayer.tv.di

import android.content.Context
import com.iptvplayer.tv.data.db.dao.FavoriteDao
import com.iptvplayer.tv.data.db.dao.WatchHistoryDao
import com.iptvplayer.tv.data.repository.AccountRepository
import com.iptvplayer.tv.data.repository.EpgRepository
import com.iptvplayer.tv.data.repository.FavoritesRepository
import com.iptvplayer.tv.data.repository.SettingsRepository
import com.iptvplayer.tv.data.repository.WatchHistoryRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        val cacheDir = File(context.cacheDir, "http_cache")
        val cacheSize = 50L * 1024 * 1024 // 50 MB

        return OkHttpClient.Builder()
            .cache(Cache(cacheDir, cacheSize))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                // Add cache headers to API responses (1 hour TTL)
                response.newBuilder()
                    .header("Cache-Control", "public, max-age=3600")
                    .removeHeader("Pragma")
                    .build()
            }
            .build()
    }

    @Provides
    @Singleton
    fun provideAccountRepository(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient
    ): AccountRepository {
        return AccountRepository(context, okHttpClient)
    }

    @Provides
    @Singleton
    fun provideFavoritesRepository(
        favoriteDao: FavoriteDao
    ): FavoritesRepository {
        return FavoritesRepository(favoriteDao)
    }

    @Provides
    @Singleton
    fun provideWatchHistoryRepository(
        watchHistoryDao: WatchHistoryDao
    ): WatchHistoryRepository {
        return WatchHistoryRepository(watchHistoryDao)
    }

    @Provides
    @Singleton
    fun provideEpgRepository(
        accountRepository: AccountRepository
    ): EpgRepository {
        return EpgRepository(accountRepository)
    }

    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context
    ): SettingsRepository {
        return SettingsRepository(context)
    }
}
