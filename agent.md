# Implementation guide for coding agents

This file is a handoff for agents implementing changes in `react-native-ota-client`. Read it together with [README.md](README.md), which describes installation, host-app setup, flavor configuration, and the public API.

## Project scope

- This package implements Android OTA updates for React Native 0.88.x using the New Architecture and Hermes. iOS OTA is not implemented.
- The package includes native Kotlin code, a TurboModule JavaScript contract, generated Codegen integration, and an example React Native app.
- OTA payloads update JavaScript and assets only. Changes to native code, permissions, React Native/Hermes versions, or the Codegen contract require a new APK.

## Main implementation areas

| Area | Location | Responsibility |
| --- | --- | --- |
| JS public API | `src/index.tsx`, `src/NativeOTAUpdates.ts` | Export `OTAUpdates` and define its TurboModule methods and status type. |
| Android module registration | `android/src/main/java/com/otasdk/OtaSdkPackage.kt` | Register the OTA module and the generated-template sample module through autolinking. |
| Host-facing SDK | `android/src/main/java/com/otasdk/OtaSdkUpdates.kt`, `OtaSdkConfig.kt` | Accept host configuration and expose startup/update operations. |
| Update orchestration | `android/src/main/java/com/otasdk/OtaUpdateManager.kt` | Check for updates, download artifacts, stage releases, and maintain activation state. |
| Release verification | `android/src/main/java/com/otasdk/OtaReleaseManifest.kt` | Parse and verify signed release metadata against the configured app, runtime, channel, and public key. |
| Bundle installation | `android/src/main/java/com/otasdk/OtaBundleInstaller.kt` | Safely extract and validate the Hermes bundle and assets before activation. |
| Activation and rollback | `android/src/main/java/com/otasdk/OtaActivationPolicy.kt` | Pure state transitions for startup, pending updates, health confirmation, and rollback. |
| Example host | `example/android/app/` | Demonstrates React Native Android build/linking. Host apps initialize `OtaSdkUpdates` in their `Application` class as described in README. |

## Lifecycle and host integration

The host app supplies `OtaSdkConfig` using its selected flavor/build-type values. In `Application.onCreate()`, it must:

1. Load the public verification key from an Android raw resource.
2. Call `OtaSdkUpdates.initialize(application, config)`.
3. Call `OtaSdkUpdates.beginProcess()` before starting React Native.
4. Pass `OtaSdkUpdates.bundlePathForReactHost()` to `getDefaultReactHost` as `jsBundleFilePath`.
5. Call `loadReactNative(this)` only after SDK startup selection.

Import the host API from `com.otasdk.OtaSdkConfig` and `com.otasdk.OtaSdkUpdates`. Do not manually register another `OtaSdkPackage`; React Native autolinking registers the package. Flavor-specific values and `debuggableVariants` are documented in README.

## Behavior and safety invariants

- Never activate a release until its signed manifest, artifact metadata, downloaded archive, extracted files, hashes, and Hermes entrypoint have been validated.
- Keep extraction constrained to the release directory; reject unsafe paths, duplicate entries, undeclared files, invalid sizes, and mismatched hashes.
- OTA artifacts are runtime-specific. A runtime-version change must invalidate stored bundles so a bundle built for another native runtime cannot start.
- Startup must fall back to the APK bundle if an active OTA bundle is missing or fails validation. A newly selected release is provisional until `markUpdateSuccessful()` confirms health; preserve rollback behavior.
- Debug builds use Metro and OTA activation is disabled. Derive this behavior from the host app's build type passed through `isDebugBuild`, not the SDK library's own `BuildConfig`.
- Only package a public verification key in the app. Never add or log a signing private key.
- Preserve input validation, release verification, and artifact integrity checks; do not disable them to make a test pass.

## Implementation workflow

1. Trace the relevant call path across the JS spec, native module, SDK entry point, manager, and policy/installer before changing behavior.
2. Keep state-transition logic in `OtaActivationPolicy` where possible; it is pure and independently testable. Keep Android I/O and persistence in the manager/installer.
3. When changing a JS TurboModule method or its argument/result shape, update `src/NativeOTAUpdates.ts` and the corresponding Kotlin module implementation together. Keep Codegen package/name conventions intact.
4. Add or update focused tests under `android/src/test/java/com/otasdk/` for policy and manifest behavior. Avoid tests that duplicate the implementation without checking observable behavior.
5. Update README when host setup, public API, flavor/build variants, or required verification steps change.
6. Run the narrow relevant checks, then run the Android unit-test task when native code or policy changes.

## Useful checks

Run from the repository root:

```sh
node .yarn/releases/yarn-4.18.1.cjs typecheck
node .yarn/releases/yarn-4.18.1.cjs lint
node .yarn/releases/yarn-4.18.1.cjs test
```

Run Android library unit tests from `example/android`:

```sh
./gradlew :react-native-ota-client:testDebugUnitTest
```

For host build/linking changes, build the example as described in README. Build tasks require the local Android SDK and a configured Java/Gradle environment.

## Change hygiene

- Follow surrounding Kotlin, TypeScript, and Gradle style; keep changes scoped to the requested behavior.
- Do not commit generated build output, local environment files, signing material, or unrelated user files.
- Report checks that were run and call out any environment limitation or failing check clearly.
