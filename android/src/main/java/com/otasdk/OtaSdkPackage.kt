package com.otasdk

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider

class OtaSdkPackage : BaseReactPackage() {
  override fun getModule(name: String, reactContext: ReactApplicationContext): NativeModule? {
    return when (name) {
      OtaSdkModule.NAME -> OtaSdkModule(reactContext)
      OtaUpdatesModule.NAME -> OtaUpdatesModule(reactContext)
      else -> null
    }
  }

  override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
    mapOf(
      OtaSdkModule.NAME to moduleInfo(OtaSdkModule.NAME, OtaSdkModule::class.java.name),
      OtaUpdatesModule.NAME to moduleInfo(OtaUpdatesModule.NAME, OtaUpdatesModule::class.java.name),
    )
  }

  private fun moduleInfo(name: String, className: String) =
      ReactModuleInfo(
          name = name,
          className = className,
          canOverrideExistingModule = false,
          needsEagerInit = false,
          isCxxModule = false,
          isTurboModule = true,
      )
}
