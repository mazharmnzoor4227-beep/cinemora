# CINEMORA — Android Streaming App

A native Android streaming app built with **Kotlin** and **Jetpack Compose**,
with the premium dark CINEMORA design and purple accent.

## What it does

- Home with a featured hero banner, language sections (**Hindi Dub** /
  **English**) and category rails (Movies, Anime, Trending) — all real content.
- Explore with live search across every source.
- Details with **all seasons**, episode lists, and **per-episode downloads**
  for offline watching.
- Real playback with Media3 ExoPlayer: hold for 2x speed, quality, audio
  language and subtitle selection inside the player, skip-intro button, and
  brightness / volume gestures.
- My List and Downloads, backed by real data. No sample or demo content.

## Built-in sources (native code, no plugins)

The catalogue comes from sources whose scraping logic is ported directly
into the app — there is no plugin or extension system, and nothing to
install after the app itself:

- **Anime:** AnimeWorldIndia (Hindi), RareAnimesIndia (Hindi), NineAnime,
  RaghavAnime, AniKotoAnime, ReAnime, Xanime
- **Movies:** IndStream, Multimovies, Vegamovies (Hindi / English dubbed
  and dual-audio)

## Build

- Android Studio Ladybug or newer, JDK 17, Android SDK 35
- `./gradlew assembleRelease` (or push to `main` — GitHub Actions builds
  the release APK automatically)

## Attribution / licences

The provider logic is ported from open-source projects, used under their
licences with credit:

- raghav extensions (KSHITIJ8473/raghav) — GPL-3.0
- Indflix extensions (dipender98/Indflix) — GPL-3.0
- CloudStream extractors (recloudstream/cloudstream) — GPL-3.0

CINEMORA's own UI design is an original concept and is not affiliated
with any other streaming brand.
