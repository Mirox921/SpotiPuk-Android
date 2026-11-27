<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png" width="112" alt="SpotiPuk app icon">
</p>

<h1 align="center">SpotiPuk for Android</h1>

<p align="center"><strong>A native Android music client for Spotify with the Ember interface.</strong></p>

<p align="center">
  <a href="#features">Features</a> ·
  <a href="#screenshots">Screenshots</a> ·
  <a href="#build-from-source">Build from source</a>
</p>

## Features

- **Now playing** with a playback queue, lock-screen controls and background audio through Android Media3.
- **Browse and search** for tracks, albums, artists, playlists and podcasts. Jump into categories with an empty search field.
- **Your library** with saved music and playlists from your Spotify account.
- **Spotify Connect and Jam** for controlling playback on compatible devices and sharing a queue in a listening session.

## Screenshots

<table align="center">
  <tr>
    <th>Now playing</th>
    <th>Search and browse</th>
  </tr>
  <tr>
    <td><img src="docs/screenshots/player.webp" width="285" alt="SpotiPuk player showing album art, playback controls and queue access"></td>
    <td><img src="docs/screenshots/search.webp" width="285" alt="SpotiPuk Search with an empty search field, browse categories and the mini player"></td>
  </tr>
</table>

## Build from source

SpotiPuk requires **Android 9 (API 28) or later**. Install **Android SDK Platform 35** and **JDK 21** to build it. Android Studio can set the SDK path when opening the project. For a command-line build, point `ANDROID_HOME` at the Android SDK.

```sh
git clone https://github.com/Mirox921/SpotiPuk-Android.git
cd SpotiPuk-Android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. Sign in with a Spotify account when you first open the app. Jam uses a hosted HTTPS service. Android builds use Kotlin, Jetpack Compose, Material 3 and Media3.

## Credits

SpotiPuk is an independent project and is unaffiliated with Spotify. Earlier versions drew on deviato's Spotifuck project. The Ember interface uses the Saira typeface. [Saira font license](docs/third-party/Saira-OFL.txt)
