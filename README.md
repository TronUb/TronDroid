# TronDroid

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
- Automatic debug and manually triggered release GitHub Actions

## Package IDs and variants
| Variant | Gradle task | Application ID |
|---|---|---|
| Debug | `assembleDevDebug` | `com.vikram.trondroid.debug` |
| Release | `assembleProdRelease` | `com.vikram.trondroid` |

The two selected variants are explicitly filtered in Gradle; the debug and release app IDs are not derived through a suffix.

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

## GitHub Actions
- **Debug:** `.github/workflows/debug.yml` runs on every push and pull request, builds `assembleDevDebug` and uploads the APK artifact.
- **Release:** `.github/workflows/release.yml` runs only via Actions → Release APK → Run workflow. It builds `assembleProdRelease`, uploads the APK and can create a GitHub Release.

The release workflow currently creates an **unsigned release APK**. Configure signing secrets and a signing step before store or public distribution. Debug and release IDs allow both variants to be installed side by side.

## Userbot interface
- Userbots tab: clean profile list, floating add button and a dedicated account detail/activity page.
- Login: country calling-code selector, phone, Telegram login code and 2-step verification.
- Deletion: confirmation dialog before removing a profile and its local TDLib session folder.
- Settings: grouped Android-style categories and a Credentials manager with copy controls. API hashes are editable and re-encrypted; TDLib session files are generated and not editable.
- Plugin tab: command prefix/handler setting (default `.`) and per-plugin command reference pages.

## Plugin catalogue and command reference
- **AFK:** `.afk`, `.back`, `.afkstatus`
- **Admin tools:** `.ban`, `.unban`, `.mute`, `.unmute`, `.kick`, `.purge`
- **Downloads:** `.download`, `.autodownload`, `.downloadstatus`
- **Welcome:** `.setwelcome`, `.delwelcome`, `.testwelcome`
- **Filters:** `.filter`, `.stop`, `.filters`
- **Scheduler:** `.schedule`, `.scheduled`, `.unschedule`

The prefix can be changed to a short custom handler. The current release includes the plugin catalogue, command documentation and persisted enable/disable switches. The commands are reference/configuration entries; the incoming-message command dispatcher and the actual Telegram-side automation handlers are not yet connected. Administrative commands must enforce Telegram permissions and operate only where the logged-in account has the necessary rights.

## Architecture
```
Compose UI → Userbot profiles/authentication → TelegramEngine
           → EasyTDLib Java/JNI + Android native libraries → Telegram MTProto
```

## Third-party notices
EasyTDLib project code is MIT-licensed. Its bundled native TDLib libraries and generated bindings are separately licensed under Boost Software License 1.0 (BSL-1.0). The bootstrap script copies upstream `THIRD_PARTY_LICENSES.txt` into `third_party/licenses/`. Preserve those notices when redistributing.
