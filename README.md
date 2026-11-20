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
- Plugin catalogue with persistent enable/disable preferences
- Automatic debug and manually triggered release GitHub Actions

## Package IDs
| Variant | Application ID |
|---|---|
| Debug | `com.vikram.trondroid-debug` |
| Release | `com.vikram.trondroid` |

## Requirements
- Android Studio with Android SDK 36
- JDK 17
- Gradle 8.13
- Android API 26+

## First-time setup
1. Clone this repository.
2. Run `bash scripts/bootstrap-easytdlib.sh`. This downloads EasyTDLib's generated TDLib Java bindings, native Android libraries, third-party notices and Gradle wrapper. These upstream binaries are not duplicated in this repository.
3. Open in Android Studio and sync, or run `./gradlew assembleDebug`.
4. Debug APK output: `app/build/outputs/apk/debug/`.

## Telegram API credentials
Create an application at [my.telegram.org](https://my.telegram.org) → API development tools. Add a profile in TronDroid and enter its API ID and API hash. The hash is encrypted locally with an AES-GCM key held by Android Keystore. Never commit credentials, session databases or 2FA passwords.

Optional Actions secrets: `TELEGRAM_API_ID` and `TELEGRAM_API_HASH`. These are build defaults only; each saved profile uses its own credentials. Secrets are not required to compile.

## GitHub Actions
- **Debug:** `.github/workflows/debug.yml` runs on every push and pull request, builds `assembleDebug` and uploads the APK artifact.
- **Release:** `.github/workflows/release.yml` runs only via Actions → Release APK → Run workflow. It builds `assembleRelease`, uploads the APK and can create a GitHub Release.

The release workflow currently creates an **unsigned release APK**. Configure signing secrets and a signing step before store or public distribution. Debug and release IDs allow both variants to be installed side by side.

## Plugin catalogue
- AFK auto-reply — away replies (runtime handler still to be implemented).
- Auto downloads — media queue (runtime handler still to be implemented).
- Group admin tools — moderation helpers requiring relevant Telegram permissions.
- Welcome messages, message filters and scheduler are extension points.

Plugin switches persist, but do not imply every automation is already executing. Runtime handlers, per-plugin configuration, foreground execution and incoming-message routing are the next implementation layer.

## Architecture
```
Compose UI → Userbot profiles/authentication → TelegramEngine
           → EasyTDLib Java/JNI + Android native libraries → Telegram MTProto
```

## Third-party notices
EasyTDLib project code is MIT-licensed. Its bundled native TDLib libraries and generated bindings are separately licensed under Boost Software License 1.0 (BSL-1.0). The bootstrap script copies upstream `THIRD_PARTY_LICENSES.txt` into `third_party/licenses/`. Preserve those notices when redistributing.
