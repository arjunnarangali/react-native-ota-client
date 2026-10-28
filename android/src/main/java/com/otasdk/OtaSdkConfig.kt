package com.otasdk

/** Values supplied by the host app for its selected flavor and build type. */
data class OtaSdkConfig(
    val serverUrl: String,
    val runtimeVersion: String,
    val channel: String,
    val baseVersion: Int,
    val publicKeyPem: ByteArray,
    val allowInsecureHttp: Boolean,
    val isDebugBuild: Boolean,
    val appId: String,
) {
  init {
    require(serverUrl.isNotBlank()) { "OTA server URL must not be blank" }
    require(runtimeVersion.isNotBlank()) { "OTA runtime version must not be blank" }
    require(channel.isNotBlank()) { "OTA channel must not be blank" }
    require(baseVersion >= 0) { "OTA base version must not be negative" }
    require(publicKeyPem.isNotEmpty()) { "OTA public verification key must not be empty" }
    require(appId.isNotBlank()) { "OTA app ID must not be blank" }
  }
}
