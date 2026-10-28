# react-native-ota-client

Android OTA update support for React Native applications using the New Architecture and Hermes. The SDK checks for signed releases, verifies the release manifest and packaged bundle, stages verified updates, selects the bundle on cold start, and rolls back a release that does not confirm healthy.

OTA can update JavaScript and bundled assets. Native code, permissions, React Native/Hermes versions, and Codegen contract changes require a new APK.

## Requirements and support

- Android only; iOS OTA support is not implemented.
- React Native 0.88.x, Android New Architecture, and Hermes.
- Android min SDK 24.
- A host application with a release-signing/public-key setup and a compatible OTA service.

## Installation

```sh
npm install react-native-ota-client
```

React Native autolinking discovers the Android package. Rebuild the native application after installation; Metro alone cannot add native code.

For local development, the example workspace consumes the SDK source directly. In a host app, install the package using the command above.

```json
## JavaScript API

```tsx
import { OTAUpdates, type OtaUpdateStatus } from 'react-native-ota-client';

const status: OtaUpdateStatus = await OTAUpdates.getStatus();
const result = await OTAUpdates.checkForUpdate();
await OTAUpdates.markUpdateSuccessful();
await OTAUpdates.closeAppToApplyUpdate();
```

`markUpdateSuccessful()` should run after the first React screen has mounted. `closeAppToApplyUpdate()` only succeeds when a verified update is staged and waiting for a restart. Debug applications report OTA activation as disabled because Metro supplies the JavaScript bundle.

The package also retains the generated-template `multiply()` example; it is not part of the OTA API.

## Android configuration

Each host application owns its flavors, IDs, runtime versions, channels, server URL, base version, public verification key, and build-type debug state. Before starting React Native, construct `OtaSdkConfig` with values from the selected app flavor and initialize the SDK:

```kotlin
val publicKeyPem = resources.openRawResource(R.raw.ota_publisher_public).use { it.readBytes() }
OtaSdkUpdates.initialize(
    this,
    OtaSdkConfig(
        serverUrl = BuildConfig.OTA_SERVER_URL,
        runtimeVersion = BuildConfig.OTA_RUNTIME_VERSION,
        channel = BuildConfig.OTA_CHANNEL,
        baseVersion = BuildConfig.OTA_BASE_VERSION,
        publicKeyPem = publicKeyPem,
        allowInsecureHttp = BuildConfig.OTA_ALLOW_INSECURE_HTTP,
        isDebugBuild = BuildConfig.DEBUG,
        appId = BuildConfig.APPLICATION_ID,
    ),
)
OtaSdkUpdates.beginProcess()
```

Do this in the host app's `Application.onCreate()` before `loadReactNative()`. Pass `OtaSdkUpdates.bundlePathForReactHost()` to React Native's host configuration so an activated OTA bundle is selected before the JS runtime starts. The SDK's own `BuildConfig.DEBUG` is not the consumer app's build type.

The public key resource contains only the verification key. Never package the publisher's signing private key in the app or SDK.

### Transport security

Use HTTPS for staging and production, with `allowInsecureHttp = false`. Local development may opt in to HTTP only when the URL host is a local development host supported by the SDK (`localhost`, `127.0.0.1`, `::1`, or Android emulator host `10.0.2.2`). The setting is not a general HTTP override. OTA download URLs must also match the configured server origin.

## Native startup operations

`OtaSdkUpdates` exposes the Android startup and host integration API:

- `initialize(context, config)` — configure the manager once before use.
- `beginProcess()` — select/rollback the startup bundle before React Native starts.
- `bundlePathForReactHost()` — return the selected bundle path, or `null` for the APK bundle.
- `status()`, `checkForUpdate()`, `markHealthy()`, and `closeAppForUpdate(activity)` — operations used by the TurboModule implementation.

The SDK autolinks a single `OtaSdkPackage` containing both the sample `OtaSdk` TurboModule and the OTA `OTAUpdates` TurboModule. Do not add another manual package registration in the host app.

## Development and verification

From the SDK package directory, install the locked Yarn dependencies with the bundled Yarn executable and build its example:

```sh
node .yarn/releases/yarn-4.18.1.cjs install
node .yarn/releases/yarn-4.18.1.cjs workspace react-native-ota-client-example build:android
```

The library unit tests run through the example's Gradle project:

```sh
cd example/android
./gradlew :react-native-ota-client:testDebugUnitTest
```

Build a development debug app to verify native linking and Metro. OTA activation requires a development release APK. The compatible FastAPI service and its deployment instructions are maintained in the standalone [`react-native-ota-server`](https://github.com/arjunnarangali/react-native-ota-server) repository. Configure the host app with its API origin, Android app ID, runtime version, channel, and public verification key; see that repository's README and API docs for the server contract.

## License

MIT
