# Developer Guide

## Environment

- JDK 17
- Flutter 3.38.7
- Android compile SDK 36
- Android NDK 28.2.13676358

Run Flutter dependency generation before Gradle when `ui/.android/include_flutter.groovy` is absent:

```bash
cd ui
flutter pub get --enforce-lockfile
```

Focused verification:

```bash
cd ui
flutter test test/services/mc_cloud_service_test.dart test/features/my/pages/account/mc_cloud_helpers_test.dart test/widgets/startup_account_prompt_test.dart
flutter analyze --no-fatal-warnings --no-fatal-infos

cd ..
./gradlew --no-daemon :baselib:testDebugUnitTest
./gradlew --no-daemon :assists:testDebugUnitTest
./gradlew --no-daemon :app:testDevelopStandardDebugUnitTest
```

Keep cloud credentials in encrypted Android storage. Keep Flutter channel payloads credential-free. Preserve local BYOK profiles and local Agent state during account migration.
