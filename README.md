# rsoundveil

An integrated Android music experience: online discovery and streaming, local music, synchronized lyrics, translation, and one unified player.

The product is designed as one experience. A single track identity connects discovery, playback, lyrics, translation, and the device library.

## Connected sources

- Internet Archive audio and video search: results are shown only when the source returns a Creative Commons or public-domain license URL. Supported files are resolved from item metadata and played in the same Media3 player.
- Jamendo: optional search and streaming through an application client_id. Download actions must respect the source's audiodownload_allowed field.
- Audius: read-only catalogue search and streaming work without credentials; an optional API key raises read-rate limits. This project does not request an API secret or write privileges.
- LRCLIB: plain, line-synchronized, and Lyricsfile word-synchronized lyrics. Lyrics and translations are cached locally for offline viewing.
- MyMemory: opt-in Indonesian translation, requested only after the listener presses Translate.

The app does not extract audio from unofficial YouTube endpoints or bypass streaming restrictions. Audio downloads are written to `Music/rsoundveil/`; licensed video downloads use `Movies/rsoundveil/`. Each provider keeps its own playback, download, and attribution rules.

## Build requirements

JDK 17, Android SDK 35, and Gradle 8.11.1. CI runs unit tests and creates a debug APK.
