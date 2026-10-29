# react-native-ota-client

Android OTA update support for React Native applications using the New Architecture and Hermes. The SDK checks for signed releases, verifies the release manifest and packaged bundle, stages verified updates, selects the bundle on cold start, and rolls back a release that does not confirm healthy.

OTA can update JavaScript and bundled assets. Native code, permissions, React Native/Hermes versions, and Codegen contract changes require a new APK.

## Requirements and support

- Android only; iOS OTA support is not implemented.
- React Native 0.88.x, Android New Architecture, and Hermes.
- Android min SDK 24.
- A host application with a release-signing setup and a public verification key.

## Installation

```sh
npm install react-native-ota-client
```

React Native autolinking discovers the Android package. Rebuild the native application after installation; Metro alone cannot add native code.

For local development, the example workspace consumes the SDK source directly. In a host app, install the package using the command above.

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

Configure the SDK once in the host application's `Application` class. Initialize it in `onCreate()` before calling `loadReactNative()`, and provide the selected bundle path to React Native's host configuration. The SDK's own `BuildConfig.DEBUG` is not the consumer app's build type.

```kotlin
import android.app.Application
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.otasdk.OtaSdkUpdates

class MainApplication : Application(), ReactApplication {
  override val reactHost: ReactHost by lazy {
    getDefaultReactHost(
      context = applicationContext,
      packageList = PackageList(this).packages,
      jsBundleFilePath = OtaSdkUpdates.bundlePathForReactHost(),
    )
  }

  override fun onCreate() {
    super.onCreate()
    val publicKeyPem = resources.openRawResource(R.raw.ota_publisher_public).use { it.readBytes() }
    val config = otaSdkConfigForCurrentVariant(publicKeyPem)
    OtaSdkUpdates.initialize(this, config)
    OtaSdkUpdates.beginProcess()
    loadReactNative(this)
  }
}
```

Call `beginProcess()` before `loadReactNative()` so the selected bundle is ready when the JS runtime starts. `bundlePathForReactHost()` returns `null` when the APK bundle should be used.

The `ota_publisher_public` Android raw resource contains only the verification key. It may be placed in `src/main/res/raw/` when shared by all variants, or in a flavor-specific source set such as `src/development/res/raw/` when variants use different keys. Never package the publisher's signing private key in the app or SDK.

### Flavor-specific configuration

Define each environment as an Android `productFlavor` and provide the SDK values as flavor-specific `BuildConfig` fields. The flavor controls the app ID, runtime version, channel, base version, and public key source set; use distinct runtime versions and channels when environments must receive separate updates. For example:

```groovy
android {
    flavorDimensions "environment"
    productFlavors {
        development {
            dimension "environment"
            applicationIdSuffix ".dev"
            buildConfigField "String", "OTA_RUNTIME_VERSION", '"rn-0.88-android-dev"'
            buildConfigField "String", "OTA_CHANNEL", '"development"'
            buildConfigField "int", "OTA_BASE_VERSION", "0"
        }
        staging {
            dimension "environment"
            applicationIdSuffix ".staging"
            buildConfigField "String", "OTA_RUNTIME_VERSION", '"rn-0.88-android-staging"'
            buildConfigField "String", "OTA_CHANNEL", '"staging"'
            buildConfigField "int", "OTA_BASE_VERSION", "0"
        }
    }
}
```

The host application's configuration helper should import `com.otasdk.OtaSdkConfig` and build a fully populated config for the selected variant. The `OTA_*` field names and types must match those read by that helper. Set `react.debuggableVariants` for every flavor's debug variants (for example, `developmentDebug` and `stagingDebug`) so React Native uses Metro for those builds. `BuildConfig.DEBUG` is supplied by the selected build type, while `BuildConfig.APPLICATION_ID` includes any flavor suffix.

## Native startup operations

`OtaSdkUpdates` exposes the Android startup and host integration API:

- `initialize(context, config)` — configure the manager once before use.
- `beginProcess()` — select/rollback the startup bundle before React Native starts.
- `bundlePathForReactHost()` — return the selected bundle path, or `null` for the APK bundle.
- `status()`, `checkForUpdate()`, `markHealthy()`, and `closeAppForUpdate(activity)` — operations used by the TurboModule implementation.

The SDK autolinks a single `OtaSdkPackage` containing both the sample `OtaSdk` TurboModule and the OTA `OTAUpdates` TurboModule. Do not add another manual package registration in the host app.

## Development and verification

For implementation architecture, behavioral invariants, suggested change workflow, and verification commands, see the [coding-agent implementation guide](agent.md).

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

Build a development debug app to verify native linking and Metro. OTA activation requires a release APK; debug builds use Metro and report activation as disabled.

## License

MIT
