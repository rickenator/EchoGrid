# Google Play Release Prep

## App Identity

- App name: EchoGrid
- Package name: `com.aniviza.echogrid`
- First beta version: `0.1.0-beta1`
- First beta versionCode: `1`
- compileSdk: `35`
- targetSdk: `35`
- minSdk: `23`
- Category: Game / Puzzle

Versioning scheme:

- `0.1.x` is for internal, closed, open, and early beta testing.
- `1.0.0` is reserved for the production-ready launch.

## Local Build Commands

```bash
./gradlew clean
./gradlew :app:assembleDebug
./gradlew :app:bundleRelease
```

Release AAB output:

```text
app/build/outputs/bundle/release/app-release.aab
```

## Local Signing Setup

Do not commit private keys or signing property files. Store the upload keystore outside the repository and create a local `keystore.properties` file in the repo root when building a signed release.

Example `keystore.properties`:

```properties
storeFile=/absolute/path/to/echogrid-upload-key.jks
storePassword=replace-with-local-password
keyAlias=echogrid
keyPassword=replace-with-local-password
```

The repository ignores `keystore.properties`, `*.jks`, `*.keystore`, and `*.p12`.

If `keystore.properties` is absent, Gradle can still build a release AAB for validation, but it will not be locally signed for Play upload. Use Play App Signing with a dedicated upload key for actual Play Console releases.

## Play Console Flow

1. Create the app in Play Console using package name `com.aniviza.echogrid`.
2. Complete app content forms, including privacy policy, data safety, ads, content rating, target audience, and app access.
3. Upload the release AAB to Internal testing.
4. Add testers and verify install, audio, haptics, landscape layout, pause/resume, and round progression.
5. Promote to Closed testing when the internal test looks stable.
6. Promote to Open testing or an Early Access-style beta when eligible.
7. Promote to Production later after testing feedback and policy review.

New personal Play developer accounts may require 12 opted-in closed testers for 14 continuous days before production or broader testing access. Tester feedback during internal, closed, and open testing is private and does not affect the public store rating.

## Permissions And Behavior

EchoGrid currently requests only `android.permission.VIBRATE` for local haptic feedback. It has no account system, no analytics SDK, no ad SDK, no billing, no tracking SDK, and no network permission.

The app intentionally runs fullscreen in sensor landscape for the current game layout.

## Store Asset TODOs

- Replace launcher icon before open testing.
- Capture current phone and tablet screenshots.
- Prepare a feature graphic before open testing.
