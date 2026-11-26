# rsoundveil

An integrated Android music experience: online discovery and streaming, local music, synchronized lyrics, translation, and one unified player.

The product is designed as one experience. A single track identity connects discovery, playback, lyrics, translation, and the device library.

## Connected sources

- Internet Archive audio search: results are shown only when the source returns a Creative Commons or public-domain license URL.
- Jamendo: optional search and streaming through an application client_id. Download actions must respect the source's audiodownload_allowed field.
- Audius: optional read-only search and streaming using an API key. This project does not request an API secret or write privileges.
- LRCLIB: plain and synchronized lyrics, with word-sync availability metadata.
- MyMemory: opt-in Indonesian translation, requested only after the listener presses Translate.

The app does not extract audio from unofficial YouTube endpoints or bypass streaming restrictions. Each provider keeps its own playback, download, and attribution rules.

## Build requirements

JDK 17, Android SDK 35, and Gradle 8.11.1. CI runs unit tests and creates a debug APK.
