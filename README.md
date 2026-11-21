<p align="center">
  <img src="https://raw.githubusercontent.com/TronUb/Tron/master/main/others/resources/images/tron-horizontal.png" alt="Tron" width="520" />
</p>

# TronDroid

**TronDroid is the Android-native continuation of [Tron](https://github.com/TronUb/Tron)** — bringing the Telegram userbot and assistant experience to Android with Kotlin, Jetpack Compose, Material 3 and TDLib.


TronDroid is an Android Telegram userbot manager built with Kotlin, Jetpack Compose, Material 3 and TDLib through the EasyTDLib Android implementation.

> Use this app only with accounts you own or are authorized to manage. Respect Telegram terms, rate limits and user privacy. Do not use it for spam, unsolicited bulk messaging or restriction evasion.

## Foundation
- Kotlin + Jetpack Compose + Material 3
- TDLib Android JNI integration based on [EasyTDLib](https://github.com/kcvabeysinghe/EasyTDlib)
- Multiple saved userbot profiles (one selected account session active in this initial shell)
- Phone, login-code and two-step verification flow
- API hashes encrypted locally using Android Keystore AES-GCM
- Per-profile TDLib database directories in app-private storage
- Categorised plugin catalogue with command reference pages and configurable command prefix
- Automatic debug builds and a rolling GitHub debug pre-release
- Manually triggered, signed production releases

## Package IDs and variants
| Variant | Gradle task | Application ID | Signing |
|---|---|---|---|
| Debug | `assembleDevDebug` | `com.vikram.trondroid.debug` | Shared CI key when secrets are available; local/fork fallback uses standard debug key |
| Release | `assembleProdRelease` | `com.vikram.trondroid` | Shared CI key |

The two selected variants are explicitly filtered in Gradle. The debug and release app IDs are separate so both can be installed side by side. Android updates an installed app only when its application ID and signing certificate match. Keep the release ID and shared keystore unchanged for every production update. CI version codes increase automatically using each workflow's run number.

## Requirements
- Android Studio with Android SDK 36
- JDK 17
- Gradle 8.13
- Android API 26+

## First-time setup
1. Clone this repository.
2. Run `bash scripts/bootstrap-easytdlib.sh`. This downloads EasyTDLib's generated TDLib Java bindings, native Android libraries, third-party notices and Gradle wrapper. These upstream binaries are not duplicated in this repository.
3. Open in Android Studio and sync, or run `./gradlew assembleDevDebug`.
4. Debug APK output: `app/build/outputs/apk/dev/debug/`.

## Telegram API credentials
Create an application at [my.telegram.org](https://my.telegram.org) → API development tools. Add a profile in TronDroid and enter its API ID and API hash. The hash is encrypted locally with an AES-GCM key held by Android Keystore. Never commit credentials, session databases or 2FA passwords.

Optional Actions secrets: `TELEGRAM_API_ID` and `TELEGRAM_API_HASH`. These are build defaults only; each saved profile uses its own credentials. Secrets are not required to compile.

## Android APK signing
The CI workflows read these repository Actions secrets:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Entire Base64-encoded PKCS12 keystore text |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias |
| `ANDROID_KEY_PASSWORD` | Private-key password |

Both CI variants use the same signing key. The debug and release application IDs are different, so they remain separate Android installations; sharing the certificate does not make one variant an update of the other. The debug workflow falls back to the standard Android debug key for forked pull requests where GitHub withholds repository secrets. The release workflow fails early if signing secrets are missing.

Never commit the keystore or passwords. Keep an offline backup of the original PKCS12 file and credentials. Losing the production key can prevent publishing compatible APK updates.

## GitHub Actions and distribution
- **Debug build:** `.github/workflows/debug.yml` runs on pushes and pull requests. It builds `assembleDevDebug`, signs with the shared key when secrets are available, and uploads a commit-specific artifact retained for 14 days.
- **Rolling debug pre-release:** successful pushes to `master` update the GitHub pre-release tagged `debug-latest`. The tag is moved to the newest master commit and the fixed-name `TronDroid-debug-latest.apk` asset is replaced. The pre-release contains the full commit SHA, short SHA, branch, commit message, workflow run link and recent commit changelog. Older commit-specific workflow artifacts remain available until their retention expires.
- **Production release:** run **Actions → Release APK → Run workflow**. Enter a new version name. It builds and signs `assembleProdRelease`, then optionally creates a versioned GitHub Release with the APK, commit ID and recent changelog.

The rolling debug pre-release is intentionally published only from `master`, so a feature-branch push cannot replace the latest mainline build. Pushes to other branches and pull requests still produce downloadable workflow artifacts.

## Userbot interface
- Userbots tab: clean profile list, floating add button and a dedicated account detail/activity page.
- Login: country calling-code selector, phone, Telegram login code and 2-step verification.
- Deletion: confirmation dialog before removing a profile and its local TDLib session folder.
- Settings: grouped Android-style categories and a Credentials manager with copy controls. API hashes are editable and re-encrypted; TDLib session files are generated and not editable.
- Plugin tab: command prefix/handler setting (default `.`) and per-plugin command reference pages.

## Tron feature migration

TronDroid follows the original project's userbot-first approach: command-driven Telegram automation, a companion bot, plugin modules, account status and configurable behaviour. The Android implementation is native Kotlin and uses TDLib rather than embedding the original Python/Pyrogram runtime.

The original Tron repository contains userbot modules for moderation, AFK, profile and group tools, downloads, media conversion, stickers, notes, privacy, search, utilities, welcome messages and diagnostics, plus assistant-bot modules for help, ping, start, broadcast and utility commands. These are the migration reference, not a claim that every original Python handler already runs on Android.

### Command usage

The Plugins screen documents each command with its syntax and a concrete example. The userbot prefix is configurable (default: `.`); bot commands use Telegram's `/` prefix.

| Command | Usage | Example |
|---|---|---|
| Wikipedia | `.wiki <query>` | `.wiki quantum mechanics` |
| IMDb | `.imdb <title>` | `.imdb Interstellar` |
| Reddit | `.reddit <query>` | `.reddit android` |
| Translate | `.translate <language> <text>` | `.translate en नमस्ते` |
| Weather | `.weather <city>` | `.weather Pune` |
| Currency | `.currency <amount> <from> <to>` | `.currency 100 USD INR` |
| QR code | `.qrcode <text or URL>` | `.qrcode https://example.com` |
| AFK | `.afk [reason]` | `.afk Having lunch` |
| Ping | `.ping` | `.ping` |
| Notes | `.save <text>` | `.save Remember this idea` |

### Port status

A plugin marked **Native Android implementation** has a Kotlin handler registered in TronDroid. **Not yet ported** means the original module is catalogued for migration but its Telegram-side behaviour is not implemented; it must not be treated as an active command. Some source features depend on server hosting, Python packages, external API credentials or desktop/host operating-system access and need Android-specific replacements rather than a direct code copy.

Dangerous or abuse-prone source capabilities (for example unrestricted shell/eval, mass messaging, unsolicited spam, or permission-bypass behaviour) are not enabled as-is. Administrative actions must validate Telegram permissions, scope and rate limits before being ported.

### Runtime and reliability

- Telegram sessions are maintained by an Android foreground service while enabled.
- Android may still restrict background work; users should allow notifications and, where the device offers it, set battery usage to unrestricted for reliable long-running sessions.
- Network requests use bounded connection/read timeouts and should fail with a user-visible error rather than block the UI.
- Keep API credentials and session data private. Credentials are encrypted with Android Keystore; never commit session databases, API hashes or bot tokens.

## Architecture
```
Compose UI → Userbot profiles/authentication → TelegramEngine
           → EasyTDLib Java/JNI + Android native libraries → Telegram MTProto
```

## Third-party notices
EasyTDLib project code is MIT-licensed. Its bundled native TDLib libraries and generated bindings are separately licensed under Boost Software License 1.0 (BSL-1.0). The bootstrap script copies upstream `THIRD_PARTY_LICENSES.txt` into `third_party/licenses/`. Preserve those notices when redistributing.
