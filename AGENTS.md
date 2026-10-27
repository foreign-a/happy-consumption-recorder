# Repository Guidelines

This repository is the Android Gradle project. Application code is under `app/src/main/java/com/happy/consumption/`, with business rules in `data/` and Compose screens in `ui/`. Maintain Chinese, English and Japanese resources in `app/src/main/res/`. Unit tests are in `app/src/test/`, device tests in `app/src/androidTest/`, and schema snapshots in `app/schemas/`.

Use JDK17, Android SDK36 and Build Tools36.0.0. Run `./gradlew testDebugUnitTest assembleDebug lintDebug`; use dedicated emulators for instrumentation because tests create synthetic accounts. Preserve integer minor-unit money, user scoping, time-zone rules and offline behavior. Do not change historical amounts when changing defaults.

Keep SDK paths untracked and signing secrets outside the repository in `~/.happy-consumption-secrets/`. Never commit credentials, keystores, private device backups or caches. For UI changes update screenshots and relevant tests. Use concise imperative commit subjects and document validation.

## GitHub Release Requirements

After completing a version update, sync the current source to the `foreign-a/happy-consumption-recorder` GitHub repository using the `foreign-a` account, update the remote `README.md` for the released behavior and download links, and publish or update the matching GitHub Release with the signed APK and SHA-256 checksum. Verify the remote source, README, release tag and downloaded assets before reporting completion. Keep signing secrets, private device data, backups and tool caches out of GitHub.

Publish v1.4 directly; do not create separate v1.3 or v1.3.1 tags or Releases unless the user later requests them. Their cumulative features remain part of the v1.4 source.

Follow explicit user corrections; add concise durable project rules only when they prevent recurring mistakes. Review any AGENTS.md diff for unintended changes.
