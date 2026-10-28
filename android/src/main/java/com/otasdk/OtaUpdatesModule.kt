package com.otasdk

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.module.annotations.ReactModule
import java.util.concurrent.Executors

@ReactModule(name = OtaUpdatesModule.NAME)
class OtaUpdatesModule(context: ReactApplicationContext) : NativeOTAUpdatesSpec(context) {
  private val executor = Executors.newSingleThreadExecutor()

  override fun getStatus(promise: Promise) {
    promise.resolve(toWritableMap(OtaSdkUpdates.status()))
  }

  override fun checkForUpdate(promise: Promise) {
    executor.execute {
      try {
        promise.resolve(toWritableMap(OtaSdkUpdates.checkForUpdate()))
      } catch (exception: Exception) {
        promise.reject("OTA_CHECK_FAILED", exception.message, exception)
      }
    }
  }

  override fun markUpdateSuccessful(promise: Promise) {
    try {
      promise.resolve(toWritableMap(OtaSdkUpdates.markHealthy()))
    } catch (exception: Exception) {
      promise.reject("OTA_CONFIRM_FAILED", exception.message, exception)
    }
  }

  override fun closeAppToApplyUpdate(promise: Promise) {
    val activity = reactApplicationContext.currentActivity
    if (activity == null) {
      promise.reject("OTA_NO_ACTIVITY", "There is no foreground Activity to close")
      return
    }
    if (OtaSdkUpdates.status()["pendingRestart"] != true) {
      promise.reject("OTA_NO_PENDING_UPDATE", "There is no verified update waiting for restart")
      return
    }

    promise.resolve(true)
    OtaSdkUpdates.closeAppForUpdate(activity)
  }

  private fun toWritableMap(values: Map<String, Any?>): WritableMap {
    val result = Arguments.createMap()
    values.forEach { (key, value) ->
      when (value) {
        null -> result.putNull(key)
        is String -> result.putString(key, value)
        is Boolean -> result.putBoolean(key, value)
        is Int -> result.putInt(key, value)
        is Long -> result.putDouble(key, value.toDouble())
        is Double -> result.putDouble(key, value)
        else -> result.putString(key, value.toString())
      }
    }
    return result
  }

  companion object {
    const val NAME = "OTAUpdates"
  }
}
