package com.otasdk

import android.app.Activity
import android.content.Context
import android.util.Log

/** Public host-app entry point for configuring and controlling OTA updates. */
object OtaSdkUpdates {
  @Volatile private var manager: OtaUpdateManager? = null

  /** Configure the SDK before starting React Native or calling any update methods. */
  @JvmStatic
  @Synchronized
  fun initialize(context: Context, config: OtaSdkConfig) {
    val appContext = context.applicationContext
    if (manager == null) {
      manager = OtaUpdateManager(appContext, config)
    }

    Log.i(
        TAG,
        "Initialized appId=${config.appId}, runtime=${config.runtimeVersion}, " +
            "channel=${config.channel}, baseVersion=${config.baseVersion}, " +
            "server=${config.serverUrl}, allowInsecureHttp=${config.allowInsecureHttp}, " +
            "isDebugBuild=${config.isDebugBuild}",
    )
  }

  @JvmStatic
  fun beginProcess() = configuredManager().beginProcess()

  @JvmStatic
  fun bundlePathForReactHost(): String? = configuredManager().bundlePathForReactHost()

  @JvmStatic
  fun status(): Map<String, Any?> = configuredManager().status()

  @JvmStatic
  fun checkForUpdate(): Map<String, Any?> = configuredManager().checkForUpdate()

  @JvmStatic
  fun markHealthy(): Map<String, Any?> = configuredManager().markHealthy()

  @JvmStatic
  fun closeAppForUpdate(activity: Activity) = configuredManager().closeAppForUpdate(activity)

  private fun configuredManager(): OtaUpdateManager =
      checkNotNull(manager) { "Call OtaSdkUpdates.initialize() before using OTA updates" }

  private const val TAG = "OtaSdkUpdates"
}
