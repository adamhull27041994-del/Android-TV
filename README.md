# Cinema TV — native Android catalogue and authorised stream player

An installable Kotlin/Jetpack Compose Android application for Android 9+ (`minSdk 28`).

## Features

- **TV discovery:** currently airing, airing today, trending television series via TMDb.
- **Films:** popular films and currently showing cinema releases.
- **Search:** movie and TV search with posters, synopsis, ratings and release dates.
- **TV episodes:** season selector, episode list, still images and air dates.
- **UK watch providers:** legal availability reported by TMDb/JustWatch, with a link to their listings.
- **Library:** persistent favourites and continue-watching history using DataStore.
- **Providers:** extensible `StreamProvider` interface, `ProviderRegistry`, and included `PersonalStreamProvider`.
- **Video playback:** Media3/ExoPlayer, fullscreen landscape player, HTTPS MP4/HLS/DASH, supported in-stream subtitle/audio track selection, request headers for custom adapters and position resume.
- **Testing:** a publicly available demo video in Settings (unrelated to catalogue titles).
- **GitHub Actions:** generates a downloadable *debug* APK on pushes to `main`.

**Limitations:** Film/TV titles listed from TMDb are metadata, not video content. The included provider returns **only links the user has explicitly added** to a specific film or TV episode. No third-party unverified Cinema HQ scraping providers are connected. TMDb watch-provider data are external viewing options and are not playable video links. DRM-protected subscription playback is not implemented.

## TMDb credentials (temporary prototype configuration)

At the owner's request, the TMDb API Read Access Token and v3 API key are committed in `tmdb.properties` so both GitHub Actions and Android Studio builds work without adding secrets. **This repository is currently public; the credentials are publicly exposed and can be recovered from both Git history and installed APKs.** Rotate the credentials and move them to secured configuration before sharing or publishing the application.

## Run in Android Studio

1. Install a recent Android Studio version with Android SDK Platform 36 and JDK 17.
2. Use **File → Open** and select this `CinemaTV` directory.
3. Sync Gradle. Gradle **8.13** and AGP **8.13.2** are configured (Gradle wrapper properties supplied; wrapper jar/script can be generated with `gradle wrapper` if needed).
4. Connect an Android 9+ device or start an emulator and click **Run**.

The app uses HTTPS network access. An internet connection and working TMDb credentials are required for live catalogue data.

## Build an APK using your existing GitHub repository

Repository: `https://github.com/adamhull27041994-del/Android-TV`

1. Open **Actions → Build Cinema TV APK**. A push to `main` triggers the workflow automatically; use **Run workflow** to rebuild manually.
2. After a successful run, open **Artifacts → CinemaTV-debug-apk** and download `app-debug.apk`.
3. Install it on Android 9 or later. The TMDb credentials are already in `tmdb.properties`.

The workflow uses GitHub-hosted Gradle 8.13; the Gradle wrapper JAR is not required in GitHub Actions. The resulting debug APK is for personal testing, not Google Play release distribution.

**Visibility warning:** The repository is **public** and the TMDb credentials are now in its commit history. Changing the repository to Private does not erase copies or protect tokens already embedded in shared APKs. Rotate them before distribution.

## How to play your authorised video URLs

Open a film or an episode → **Choose streaming source** / tap the episode → **Add an authorised stream URL** → enter an HTTPS `.mp4`, `.m3u8` or `.mpd` URL → choose format if auto-detection isn't possible → **Save** → **Play**.

Sources are stored **on the device**, not in GitHub. This makes testing safe for your personal media URLs. For servers requiring request headers, implement a `StreamProvider` that supplies the headers in `PlayableStream.headers`.

### Add another source integration

See `app/src/main/java/uk/co/cinematv/provider/StreamProviders.kt`:

```kotlin
class MyLicensedProvider : StreamProvider {
    override val name = "My subscription / personal server"
    override suspend fun resolve(key: StreamKey): List<PlayableStream> {
        // Query an API you are authorised to access.
        // Match key.media.id, key.media.type, key.season and key.episode.
        // Return only actual authorised playback URLs.
        return emptyList()
    }
}
```

Then add `MyLicensedProvider(...)` to the `ProviderRegistry` in `CinemaViewModel`.

Useful future providers: personal Jellyfin/Plex libraries with authorised API access; licensing-dependent streaming partners; your own NAS or private HLS server. The app does not bypass access controls or DRM.

## Repo layout

- `app/src/main/java/uk/co/cinematv/MainActivity.kt` — complete Compose user interface.
- `data/TmdbApi.kt` — TMDb catalogue client.
- `data/LibraryStore.kt` — DataStore favourites, sources and playback history.
- `ui/CinemaViewModel.kt` — state, search, catalogue and source loading.
- `provider/StreamProviders.kt` — plugin architecture and personal stream provider.
- `player/PlayerActivity.kt` — Media3 video playback.
- `.github/workflows/android-apk.yml` — automated APK build.
- `tmdb.properties` — blank placeholders; GitHub Actions fills these from repository secrets.

## Legal, data, and provider notices

This product uses the TMDb API but is not endorsed or certified by TMDb. TMDb data and imagery remain subject to its usage terms; watch-provider metadata in TMDb is provided through JustWatch. The demo video link is supplied solely to test the player. Use your own lawfully accessible sources; do not assume a public provider domain grants redistribution or streaming rights.
