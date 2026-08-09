# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

A custom Android radio + podcast app (single-module, Kotlin, Jetpack Compose, Media3/ExoPlayer) with a hand-curated list of public-broadcaster live streams (NL/BE/DE/FR/UK) and podcast RSS feeds. Target device: the owner's Android 15 phone. No tests, no CI, not on an app store — installed directly via adb.

## Build & install

Gradle needs the Android Studio JDK (plain `java` is not installed on this machine):

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug                      # build APK
./gradlew installDebug                       # build + install on connected phone
adb install -r app/build/outputs/apk/debug/app-debug.apk   # alternative install
```

There are no tests. `./gradlew lint` runs Android Lint if needed.

## Architecture

All code lives in `app/src/main/java/nl/wijnand/radio/` (package `nl.wijnand.radio`), one file per concern:

- **Stations.kt** — the curated live-stream list, a static `object Stations`. Stations are plain data (`id`, name, broadcaster, country, stream URL). BBC entries are HLS (`.m3u8` over http, hence `usesCleartextTraffic` in the manifest); everything else is Icecast MP3. To add/change a station, edit this list only — the UI groups by `country` automatically.
- **Podcasts.kt** — curated podcast feeds (`CuratedPodcasts`) plus `RssFetcher`, a dependency-free RSS parser (HttpURLConnection + XmlPullParser, manual redirect handling because HttpURLConnection won't follow cross-protocol redirects).
- **Schedule.kt** — today's programme guide per station. Four source types, dispatched by station id in `ScheduleFetcher`: NPO sites' keyless JSON (`/api/broadcasts`), BBC's RMS JSON, RadioDNS/SPI XML for WDR (ARD server, fm bearer path) and Deutschlandradio (own server, dab bearer path), and VRT MAX's GraphQL API (`vrtnu-api/graphql/v1`, anonymous with `x-vrt-client-name`/`-version` headers — undocumented internal API, expect breakage; Klara Continuo has no guide). Radio France works via its GraphQL API only when `radiofrance.api.key` is set. RTBF has no full-day source; its Auvio bff-service only exposes now-playing (VRT's RadioDNS schedule files exist but are empty — both verified 2026-08).
- **PlaybackService.kt** — a Media3 `MediaSessionService` owning the ExoPlayer instance. Playback survives the activity; the media notification comes from Media3 automatically.
- **MainActivity.kt** — connects a `MediaController` to the service in `onStart`/`onStop` and hosts the Compose UI. All playback commands from the UI go through this controller, never a local player.
- **AppViewModel.kt** — episode fetching/cache state and user-added custom feeds (persisted in SharedPreferences `radio` / key `custom_feeds` as `"title\turl"` string-set entries).
- **Ui.kt** — the entire Compose UI: two tabs (Radio, Podcasts), episode list with in-tab navigation (no Navigation library — `selected` state + `BackHandler`), and the bottom `PlayerBar`. `rememberPlayerState` bridges `Player.Listener` events into Compose state; media IDs are namespaced `station:<id>` / `episode:<audioUrl>` and the UI uses them to highlight what's playing. Live streams show a Stop button (pause is meaningless), podcasts show Pause + seek slider.

UI language is Dutch — keep new user-facing strings in Dutch.

## Radio France API constraints

`NOTITIES-radiofrance-cgu.md` summarizes the Radio France Open API terms. When extending the app, respect the hard limits: max 1000 API requests/day (cache the schedule, never poll), no downloading/caching/rehosting of Radio France audio (streaming via their URLs only), no modification of their programme data, and the API key (in `local.properties` as `radiofrance.api.key`, read into `BuildConfig.RADIOFRANCE_API_KEY`) must never be committed or shipped in an APK that gets shared — build a keyless variant for that. The credit line "données fournies par l'Open API Radio France" in the French stations' schedule screen is contractually required; don't remove it.

## Stream/feed maintenance

Broadcasters occasionally move stream URLs. Verify a candidate with:

```bash
curl -s --max-time 8 -o /dev/null -r 0-4096 -L -w "%{http_code} %{content_type}\n" "<URL>"
```

Expect 200/206 with an audio/* content type (or a `.m3u8` playlist type for BBC HLS).
