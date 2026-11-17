package com.cinemora.app

import android.app.Application
import android.content.Context
import com.cinemora.app.core.Repository
import com.cinemora.app.providers.AnikotoProvider
import com.cinemora.app.providers.AnimeWorldProvider
import com.cinemora.app.providers.GenericExtractors
import com.cinemora.app.providers.IndStreamProvider
import com.cinemora.app.providers.MultimoviesProvider
import com.cinemora.app.providers.NineAnimeProvider
import com.cinemora.app.providers.RaghavAnimeProvider
import com.cinemora.app.providers.RareAnimesProvider
import com.cinemora.app.providers.ReAnimeProvider
import com.cinemora.app.providers.VegamoviesProvider
import com.cinemora.app.providers.XanimeProvider

/**
 * Holds the app-wide singletons. The repository aggregates the built-in
 * native providers (anime + movies). There is no plugin/extension system:
 * every source is compiled-in code.
 */
class CinemoraApplication : Application() {

    companion object {
        lateinit var context: Context
            private set

        lateinit var repository: Repository
            private set
    }

    override fun onCreate() {
        super.onCreate()
        context = applicationContext
        GenericExtractors.registerAll()
        repository = Repository(
            listOf(
                // Anime (Hindi dub first)
                AnimeWorldProvider(),
                RareAnimesProvider(),
                NineAnimeProvider(),
                RaghavAnimeProvider(),
                AnikotoProvider(),
                ReAnimeProvider(),
                XanimeProvider(),
                // Movies (Hindi / English dubbed + dual-audio)
                IndStreamProvider(),
                MultimoviesProvider(),
                VegamoviesProvider(),
            ),
        )
    }
}

/** Context holder used by providers that need it (Cloudflare WebView bypass). */
object CinemoraAppHolder {
    val context: Context
        get() = CinemoraApplication.context
}
