# AstraGram for Android

**AstraGram** is an independent Android client based on the Telegram for Android source code. It keeps the familiar Telegram experience and adds optional tools for media, automation and project services.

> AstraGram is not affiliated with Telegram, Yandex or exteraGram. Their names and services belong to their respective owners.

[Latest release](https://github.com/FoxCoderGit/AstraGram/releases/latest) | [Report an issue](https://github.com/FoxCoderGit/AstraGram/issues) | [Security policy](SECURITY.md)

## Current release

- Version: **12.10.6-astra.121**
- Android package: `org.ghostgram.messenger` - retained to support updates over earlier installed builds without deleting local application data.
- Download: [GitHub Releases](https://github.com/FoxCoderGit/AstraGram/releases/latest)

## AstraGram additions

The following capabilities are added or extended beyond the standard Telegram Android client.

For the complete user-facing feature catalogue, see [FEATURES.md](FEATURES.md).

### Media and productivity

- **Video link download** - optional yt-dlp integration with FFmpeg conversion. A supported public link can be queued as a video and sent after download completes.
- **Background media tasks** - queued downloads and uploads continue outside the current chat and are visible in the background-task screen.
- **Attachment menu layout** - rearrange supported attachment actions by drag and drop.
- **Drawing for voice messages** - optional visual drawing mode for creating a silent voice-message animation.
- **Yandex Music** - optional search, favourites and audio sending. Each user signs in with their own Yandex account or token; AstraGram does not ship a shared music account.

### Messaging and interface

- **Reaction notifications** - Android notifications when another user reacts to your message, including while the app is in the background.
- **Plugin controls** - built-in optional features are enabled independently from the Plugins screen.
- **AstraGram badges** - server-verified creator and supporter badges with an explanation sheet on tap.
- **exteraGram supporter badges** - optional display of supporter badges sourced from the public exteraGram profile service.

### Reliability and releases

- **Signed in-app updates** - background update checks, `versionCode` comparison, release notes, retry/resume support and APK SHA-256/signature validation before installation.
- **Personal media cloud** - optional upload workflow using a private Telegram channel owned by the current account.
- **Background connection support** - persistent connection controls intended to improve delivery reliability where Google push services are unavailable.

## Privacy and security

- The public repository does not contain the AstraGram client API values, signing key, administrator credentials, server private keys or user tokens.
- Client API values are supplied through the ignored `astra.local.properties` file and are embedded only into a locally built APK. Anything embedded in an APK can be extracted; never place server passwords or private keys there.
- Releases should be downloaded only from the [official GitHub Releases page](https://github.com/FoxCoderGit/AstraGram/releases/latest) and verified with the published SHA-256 checksum.
- Optional integrations may send requests to their respective external services. Read each provider's terms before using it.

## Build from source

### Requirements

- Android Studio JDK
- Android SDK 36 and Build Tools 36.0.0
- Android NDK 27.2.12479018
- CMake 3.22.1
- Python 3.11 or newer

### Private client configuration

Copy `astra.local.properties.example` to `astra.local.properties` and set the client values locally. The actual file is ignored by Git.

```properties
astraApiId=YOUR_TELEGRAM_API_ID
astraApiHash=YOUR_TELEGRAM_API_HASH
astraMapsApiKey=YOUR_GOOGLE_MAPS_API_KEY
```

Do not commit this file or add server credentials to it.

### Build command

```powershell
.\gradlew.bat :TMessagesProj_Ghost:assembleDebug -PghostOnly=true -Pandroid.injected.testOnly=false --max-workers=2 --console=plain
```

The APK is written to `TMessagesProj_Ghost/build/outputs/apk/debug/`.

For the project's signed local distribution workflow, use `Tools/build-private-apk.ps1`. It validates the resulting APK before installation or copying to a connected Android device.

## Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md) before opening an issue or pull request. Do not include secrets, private account data, session files or APK signing material in reports.

## License and upstream

This project is based on [Telegram for Android](https://github.com/DrKLO/Telegram). The original licence and notices for Telegram and third-party components remain in this repository. See [LICENSE](LICENSE) and the relevant source directories for details.
