# Changelog

All notable project changes are documented here. Automated debug pre-releases also include the exact commit SHA and a generated recent-commit summary.

## Unreleased

### Build and distribution
- Configure one shared PKCS12 signing key for CI debug and production release APKs.
- Publish a rolling `debug-latest` GitHub pre-release from successful pushes to `master`; replace its APK asset and move its tag to the newest commit.
- Attach build metadata and recent commit summaries to debug and production distributions.
- Keep per-commit debug workflow artifacts for 14 days and production artifacts for 90 days.

### Project
- Add the initial TronDroid Android/Kotlin project foundation, userbot profile management, TDLib authentication shell, credential manager and plugin catalogue.
