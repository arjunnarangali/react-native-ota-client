# react-native-ota-client

Android and iOS OTA update support for React Native applications using the New Architecture and Hermes. The SDK checks for signed releases, verifies the release manifest and packaged bundle, stages verified updates, selects the bundle on cold start, and rolls back a release that does not confirm healthy.

OTA can update JavaScript and bundled assets. Native code, permissions/entitlements, React Native/Hermes versions, and Codegen contract changes require a new APK/IPA.

## Requirements and support

- Android and iOS.
- React Native 0.88.x, Android New Architecture, and Hermes.
- Android min SDK 24.
- iOS New Architecture and Hermes; use the host app's iOS deployment target supported by its React Native version.
- A host application with a release-signing setup and a public verification key.

## Installation

```sh
npm install react-native-ota-client
```

React Native autolinking discovers the native package on Android and iOS. Rebuild the native application after installation; Metro alone cannot add native code.

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

On iOS, apps cannot terminate themselves. When `closeAppToApplyUpdate()` returns `true`, ask the user to leave and reopen the app to load the staged bundle.

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
import com.otasdk.OtaSdkConfig
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
    loadReactNative(this)
  }
}
```

Call `beginProcess()` before `loadReactNative()` so the selected bundle is ready when the JS runtime starts. `bundlePathForReactHost()` returns `null` when the APK bundle should be used.

The `ota_publisher_public` Android raw resource contains only the verification key. It may be placed in `src/main/res/raw/` when shared by all variants, or in a flavor-specific source set such as `src/development/res/raw/` when variants use different keys. Never package the publisher's signing private key in the app or SDK.

### Flavor-specific configuration

Define each environment as an Android `productFlavor` and provide the SDK values as flavor-specific `BuildConfig` fields. The flavor controls the app ID, runtime version, channel, server URL, base version, public verification key, and build-type debug state; use distinct runtime versions and channels when environments must receive separate updates. For example:

```groovy
android {
    flavorDimensions "environment"
    productFlavors {
        development {
            dimension "environment"
            applicationIdSuffix ".dev"
            buildConfigField "String", "OTA_SERVER_URL", '"https://dev.example.com"'
            buildConfigField "String", "OTA_RUNTIME_VERSION", '"rn-0.88-android-dev"'
            buildConfigField "String", "OTA_CHANNEL", '"development"'
            buildConfigField "int", "OTA_BASE_VERSION", "0"
            buildConfigField "boolean", "OTA_ALLOW_INSECURE_HTTP", "false"
        }
        staging {
            dimension "environment"
            applicationIdSuffix ".staging"
            buildConfigField "String", "OTA_SERVER_URL", '"https://staging.example.com"'
            buildConfigField "String", "OTA_RUNTIME_VERSION", '"rn-0.88-android-staging"'
            buildConfigField "String", "OTA_CHANNEL", '"staging"'
            buildConfigField "int", "OTA_BASE_VERSION", "0"
            buildConfigField "boolean", "OTA_ALLOW_INSECURE_HTTP", "false"
        }
    }
}
```

The `OTA_*` field names and types must match those read in `MainApplication.kt`. Set `react.debuggableVariants` for every flavor's debug variants (for example, `developmentDebug` and `stagingDebug`) so React Native uses Metro for those builds. `BuildConfig.DEBUG` is supplied by the selected build type, while `BuildConfig.APPLICATION_ID` includes any flavor suffix.

### Transport security

Use HTTPS for staging and production, with `allowInsecureHttp = false`. Local development may opt in to HTTP only when the URL host is a local development host supported by the SDK (`localhost`, `127.0.0.1`, `::1`, or Android emulator host `10.0.2.2`). The setting is not a general HTTP override. OTA download URLs must also match the configured server origin.

## Native startup operations

`OtaSdkUpdates` exposes the Android startup and host integration API:

- `initialize(context, config)` — configure the manager once before use.
- `beginProcess()` — select/rollback the startup bundle before React Native starts.
- `bundlePathForReactHost()` — return the selected bundle path, or `null` for the APK bundle.
- `status()`, `checkForUpdate()`, `markHealthy()`, and `closeAppForUpdate(activity)` — operations used by the TurboModule implementation.

The SDK autolinks a single `OtaSdkPackage` containing both the sample `OtaSdk` TurboModule and the OTA `OTAUpdates` TurboModule. Do not add another manual package registration in the host app.

## iOS configuration

Configure the SDK in the host app before creating the React Native factory/runtime. `beginProcess()` must run before startup bundle selection. In `RCTDefaultReactNativeFactoryDelegate.bundleURL()`, retain Metro in Debug and use the OTA URL before the embedded bundle in Release:

```swift
import OtaSdk

// In AppDelegate, before the SceneDelegate starts React Native:
OtaSdkUpdates.initialize(
  serverUrl: serverURL,
  runtimeVersion: runtimeVersion,
  channel: channel,
  baseVersion: NSNumber(value: baseVersion),
  publicKeyPem: publicKeyPEM,
  isDebugBuild: isDebugBuild,
  appId: appId,
  allowLocalHttp: allowLocalHTTP
)
OtaSdkUpdates.beginProcess()

// In the React Native factory delegate:
override func bundleURL() -> URL? {
  #if DEBUG
  RCTBundleURLProvider.sharedSettings().jsBundleURL(forBundleRoot: "index")
  #else
  OtaSdkUpdates.bundleURL() ?? Bundle.main.url(forResource: "main", withExtension: "jsbundle")
  #endif
}
```

Provide the configuration from host-owned build settings and bundle only the public verification key. Use HTTPS for release builds. `allowLocalHttp` is for explicit local development only and is restricted to `localhost`, `127.0.0.1`, or `::1`; OTA downloads must match the configured server origin. iOS returns whether an update is staged from `closeAppToApplyUpdate()` and requires the user to relaunch. Call `markUpdateSuccessful()` after the first usable React screen mounts.

For the companion OTA server API and release contract, see the [`react-native-ota-server` GitHub repository](https://github.com/arjunnarangali/react-native-ota-server).

## License

MIT
