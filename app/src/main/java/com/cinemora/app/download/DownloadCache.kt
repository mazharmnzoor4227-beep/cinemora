package com.cinemora.app.download

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/** Shared on-disk cache used by both the player and the download service. */
object DownloadCache {
    private var cache: SimpleCache? = null

    @Synchronized
    fun get(context: Context): SimpleCache {
        return cache ?: SimpleCache(
            File(context.filesDir, "download_cache"),
            LeastRecentlyUsedCacheEvictor(2L * 1024 * 1024 * 1024),
            StandaloneDatabaseProvider(context),
        ).also { cache = it }
    }
}
