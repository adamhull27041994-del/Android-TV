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

## Automatic sources in this revision

- **WatchOut**: For TV, fetches `https://raw.githubusercontent.com/Watchout2025/api/refs/heads/main/hls/tv/{tmdbId}/S{season}.json`; retrieves the selected episode number from the JSON map. For movies, reads the text link at `hls/movie/{tmdbId}`. Treats `404` as no match and forces HLS MIME for playlist URLs with a `.txt` suffix. The native Media3 player handles actual direct links.
- **VidLink**: Uses the provider's documented `/tv/{tmdbId}/{season}/{episode}` and `/movie/{tmdbId}` HTML embeds in a separate WebView. This is NOT an extracted, native Media3 stream, and its availability is unknown until opened.
- **Personal sources**: Explicitly added streams remain available and deletable as before.

Provider endpoints can break or change without warning. These providers are independent of this project and do not certify any licensing or right to view content. The original APK contains many other providers whose request protocols or responses have not yet been implemented.


## Additional source adapters (APK investigation)

This revision adds YaStream, VidBolt and ThugTV direct JSON resolvers and a 2Embed HTML-player fallback. Their routes and expected output fields are reconstructed from the supplied Cinema HQ v9.1 APK, not from a documented, stable provider contract. Entries are only displayed as direct streams when an HTTPS video-looking URL is returned. Service availability, actual playback and streaming rights have not been verified. All source requests run concurrently so slow/offline services do not block other results. Providers needing decryption, dynamic player keys or debrid tokens remain unimplemented.


## VOE video-host links

Cinema HQ's supplied APK did not have plain-text VOE references in its three DEX files.
VOE does not expose a public TMDb-to-episode streaming catalogue; the official API
lists files belonging to the authenticated VOE account.

This app supports VOE as a **hosted player link**, not an automatic TV provider.
Users can save an authorised VOE share URL (for example,
https://voe.sx/abc123456789) or embed URL (https://voe.sx/e/abc123456789)
against a specific movie or episode. A supported link returned inside a provider
JSON response is likewise recognised as a hosted link. Both cases launch the
official VOE player in the device browser, preserving the site's normal controls.
We do not extract VOE CDN URLs or attempt to bypass the site's advertising or
access restrictions. This does not guarantee that a given VOE file is available.


## Screen-awake playback and History tab

The native Cinema TV player and the in-app web player request Android's `FLAG_KEEP_SCREEN_ON` while open, preventing normal inactivity dimming and locking. The flag is cleared on exit. A new **History** bottom tab uses on-device DataStore records to show individual movies and TV episodes with series name, season/episode number, optional episode title, last watched date, playback percentage, and resume/watch-again. Progress is periodically saved during native playback. Previous history stays compatible. Web-only embeds generally cannot expose reliable watch position to the app and therefore do not create native watch records.


## November 20 2026 — screen-awake compatibility update

The custom Media3 player and the in-app HTML player now keep their **foreground
window**, Compose root, video surface and fullscreen web video view awake.
An additional `SCREEN_BRIGHT_WAKE_LOCK` compatibility fallback is acquired
only while the playback Activity is resumed, with a four-hour fail-safe timeout.
It is released on pause, navigation away and destruction; the device can still
be locked manually. Android explicitly recommends the window flag, which is
still used, but a legacy wake lock can help on some devices and multi-display
setups. The fallback requires the normal `WAKE_LOCK` permission.

To confirm native player behaviour, open **Settings → Test video player**
inside Cinema TV and allow the phone's normal timeout interval to pass.
For a WebView embed, test an in-app VidLink or 2Embed entry separately.
VOE and RiveStream open *external apps/browsers*; Cinema TV cannot control
the wake state of those windows, Android Auto host displays or hardware
screen-lock policies. A persistent timeout inside native playback can be
diagnosed with `adb logcat -s CinemaTV_Screen:D`.
