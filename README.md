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

## TMDb credentials (safe for a public repository)

**This repository currently has public visibility**, so private credentials are not committed.

For GitHub Actions, open **Settings → Secrets and variables → Actions → New repository secret** and create `TMDB_BEARER` (TMDb API Read Access Token) and `TMDB_API_KEY` (TMDb v3 key). The workflow loads them into `tmdb.properties` only on its runner.

For Android Studio, edit the local `tmdb.properties` file. This file contains blank placeholders in the public repository. Keep your local real values private; do not commit them. Note that the APK embeds whichever keys it was built with, so they can be extracted from the app by an advanced user.

## Run in Android Studio

1. Install a recent Android Studio version with Android SDK Platform 36 and JDK 17.
2. Use **File → Open** and select this `CinemaTV` directory.
3. Sync Gradle. Gradle **8.13** and AGP **8.13.2** are configured (Gradle wrapper properties supplied; wrapper jar/script can be generated with `gradle wrapper` if needed).
4. Connect an Android 9+ device or start an emulator and click **Run**.

The app uses HTTPS network access. An internet connection and working TMDb credentials are required for live catalogue data.

## Build APK with GitHub Actions (without Android Studio)

1. Create a **new private repository** named `CinemaTV` in your own GitHub account.
2. Upload **the contents of this folder**, including `.github/workflows/android-apk.yml`. Make sure the `.github` folder is included.
3. Commit files to the default `main` branch.
4. Open **Actions → Build Cinema TV APK**, then click **Run workflow** (or let the push trigger it automatically).
5. When successful, open that run → **Artifacts** → **CinemaTV-debug-apk**. Download and extract it to get `app-debug.apk`.
6. Install the APK on your Android phone. If Android asks, allow installation from your file manager/browser for this app only.

The workflow uses GitHub-hosted Gradle 8.13; no `gradlew` script or wrapper JAR is required on GitHub Actions. The resulting `debug` APK is suitable for personal testing, **not for publishing on Google Play**.

### GitHub CLI shortcut

If you have GitHub CLI (`gh`) installed and authenticated, run the following from the root of the extracted project:

```bash
git init
git add .
git commit -m "Initial Cinema TV native Android app"
git branch -M main
gh repo create CinemaTV --private --source=. --remote=origin --push
```

If `gh` is not available, create the private repo in GitHub's web UI and upload these files, or use GitHub Desktop.

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