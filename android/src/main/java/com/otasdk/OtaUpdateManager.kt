package com.otasdk

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

internal class OtaUpdateManager(context: Context, private val config: OtaSdkConfig) {
  private val appContext = context.applicationContext
  private val preferences: SharedPreferences =
      appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
  private val stateLock = Any()
  private val otaRoot = File(appContext.filesDir, "ota")
  private val updatesDirectory = File(otaRoot, "updates")
  private val stagingDirectory = File(otaRoot, "staging")
  private val publicKeyPem: ByteArray = config.publicKeyPem.copyOf()

  @Volatile private var startupBundlePath: String? = null

  /** Called from Application.onCreate before ReactHost is initialized. */
  fun beginProcess() {
    if (config.isDebugBuild) {
      startupBundlePath = null
      return
    }

    synchronized(stateLock) {
      val savedRuntime = preferences.getString(KEY_RUNTIME_VERSION, null)
      if (savedRuntime != null && savedRuntime != config.runtimeVersion) {
        // OTA bundles are native-runtime specific. Never boot a bundle from another APK runtime.
        preferences.edit().clear().putString(KEY_RUNTIME_VERSION, config.runtimeVersion).commit()
        updatesDirectory.deleteRecursively()
        stagingDirectory.deleteRecursively()
      } else if (savedRuntime == null) {
        preferences.edit().putString(KEY_RUNTIME_VERSION, config.runtimeVersion).commit()
      }

      val before = readActivationState()
      val transition = OtaActivationPolicy.beginStartup(before)
      persistActivationState(transition.state)
      transition.failedReleaseId?.let { File(updatesDirectory, it).deleteRecursively() }

      val selected = transition.selectedReleaseId?.let(::bundleFile)
      if (selected != null && selected.isFile) {
        startupBundlePath = selected.absolutePath
      } else if (transition.selectedReleaseId != null) {
        // A missing active bundle must never prevent the APK's embedded bundle from starting.
        val currentState = readActivationState()
        val fallback =
            if (currentState.pendingReleaseId != null) {
              OtaActivationPolicy.beginStartup(
                      currentState.copy(pendingLaunchAttempted = true)
                  )
                  .state
            } else {
              currentState.copy(activeReleaseId = null, activeVersion = config.baseVersion)
            }
        persistActivationState(
            fallback.copy(
                pendingReleaseId = null,
                pendingVersion = null,
                previousReleaseId = null,
                previousVersion = null,
                pendingLaunchAttempted = false,
            )
        )
        startupBundlePath = null
      } else {
        startupBundlePath = null
      }
    }
  }

  fun bundlePathForReactHost(): String? = startupBundlePath

  fun status(): Map<String, Any?> = synchronized(stateLock) {
    val state = readActivationState()
    val pending = state.pendingReleaseId != null
    mapOf(
        "enabled" to !config.isDebugBuild,
        "status" to if (config.isDebugBuild) "debug_build" else if (pending) "restart_required" else "ready",
        "message" to if (config.isDebugBuild) {
          "OTA activation is tested with a release APK; debug builds load Metro."
        } else if (pending) {
          "A verified update is ready. Close and reopen the app to apply it."
        } else {
          "This app is using its active bundle."
        },
        "runtimeVersion" to config.runtimeVersion,
        "channel" to config.channel,
        "currentVersion" to state.activeVersion,
        "pendingVersion" to state.pendingVersion,
        "pendingRestart" to pending,
        "updateAvailable" to pending,
        "action" to null,
    )
  }

  fun markHealthy(): Map<String, Any?> = synchronized(stateLock) {
    if (config.isDebugBuild) return status()
    val before = readActivationState()
    val after = OtaActivationPolicy.markHealthy(before)
    if (after != before) {
      persistActivationState(after)
      Log.i(TAG, "OTA release ${after.activeReleaseId} confirmed healthy")
    }
    status()
  }

  fun checkForUpdate(): Map<String, Any?> {
    if (config.isDebugBuild) return status()
    if (!supportsConfiguredTransport()) {
      return result(
          "error",
          "HTTPS is required unless the host app explicitly allows local development HTTP.",
      )
    }

    val currentState = synchronized(stateLock) { readActivationState() }
    if (currentState.pendingReleaseId != null) return status()

    return try {
      val response = requestUpdateCheck(currentState.activeVersion)
      if (!response.has("update_available")) {
        throw OtaVerificationException("Update server response is missing update_available")
      }
      if (!response.getBoolean("update_available")) {
        return result("up_to_date", "No compatible update is available.")
      }

      val releaseJson = response.getJSONObject("release")
      val verified =
          OtaReleaseVerifier.verify(
              releaseJson = releaseJson,
              publicKeyPem = publicKeyPem,
              expectedAppId = config.appId,
              expectedRuntimeVersion = config.runtimeVersion,
              expectedChannel = config.channel,
              currentVersion = currentState.activeVersion,
          )
      validateDownloadUrl(verified.downloadUrl)

      val existingDirectory = OtaBundleInstaller.releaseDirectory(updatesDirectory, verified.manifest.releaseId)
      if (!OtaBundleInstaller.verifyExisting(existingDirectory, verified)) {
        val archive = downloadArchive(verified)
        try {
          OtaBundleInstaller.install(archive, updatesDirectory, verified)
        } finally {
          archive.delete()
        }
      }

      synchronized(stateLock) {
        val latestState = readActivationState()
        if (latestState.pendingReleaseId != null) return status()
        val staged = OtaActivationPolicy.stage(
            latestState,
            verified.manifest.releaseId,
            verified.manifest.version,
        )
        persistActivationState(staged)
      }
      result(
          "restart_required",
          "Verified release ${verified.manifest.version} is ready. Close and reopen the app to apply it.",
          pendingVersion = verified.manifest.version,
          action = verified.action,
      )
    } catch (exception: Exception) {
      Log.w(TAG, "OTA update check failed", exception)
      result("error", exception.message ?: "The update could not be verified or downloaded.")
    }
  }

  fun closeAppForUpdate(currentActivity: android.app.Activity) {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
      currentActivity.finishAndRemoveTask()
      android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
          { android.os.Process.killProcess(android.os.Process.myPid()) },
          150,
      )
    }
  }

  private fun requestUpdateCheck(currentVersion: Int): JSONObject {
    val installationId =
        preferences.getString(KEY_INSTALLATION_ID, null) ?: UUID.randomUUID().toString().also {
          check(preferences.edit().putString(KEY_INSTALLATION_ID, it).commit()) {
            "Could not persist installation ID"
          }
        }
    val requestBody =
        JSONObject()
            .put("platform", "android")
            .put("app_id", config.appId)
            .put("runtime_version", config.runtimeVersion)
            .put("channel", config.channel)
            .put("current_version", currentVersion)
            .put("installation_id", installationId)
            .put("failed_release_id", readActivationState().failedReleaseId ?: JSONObject.NULL)

    val url = URL("${config.serverUrl.trimEnd('/')}/api/v1/updates/check")
    val connection = openConnection(url).apply {
      requestMethod = "POST"
      doOutput = true
      setRequestProperty("Content-Type", "application/json; charset=utf-8")
      setRequestProperty("Accept", "application/json")
    }
    try {
      connection.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }
      val code = connection.responseCode
      val stream = if (code in 200..299) connection.inputStream else connection.errorStream
      val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
      if (code !in 200..299) throw OtaVerificationException("Update server returned HTTP $code")
      return JSONObject(body)
    } finally {
      connection.disconnect()
    }
  }

  private fun downloadArchive(release: VerifiedOtaRelease): File {
    validateDownloadUrl(release.downloadUrl)
    stagingDirectory.mkdirs()
    val archive = File(stagingDirectory, "${release.manifest.releaseId}.zip.part")
    archive.delete()
    val connection = openConnection(URL(release.downloadUrl)).apply {
      requestMethod = "GET"
      setRequestProperty("Accept", "application/zip")
    }
    try {
      val code = connection.responseCode
      if (code != HttpURLConnection.HTTP_OK) {
        throw OtaVerificationException("Bundle server returned HTTP $code")
      }
      val contentLength = connection.contentLengthLong
      if (contentLength > MAX_ARTIFACT_BYTES ||
          (contentLength >= 0L && contentLength != release.manifest.artifactSizeBytes)) {
        throw OtaVerificationException("Bundle download size does not match the signed manifest")
      }

      val digest = MessageDigest.getInstance("SHA-256")
      var total = 0L
      connection.inputStream.use { input ->
        FileOutputStream(archive).use { output ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_ARTIFACT_BYTES || total > release.manifest.artifactSizeBytes) {
              throw OtaVerificationException("Bundle exceeds the permitted download size")
            }
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
          }
          output.fd.sync()
        }
      }
      if (total != release.manifest.artifactSizeBytes ||
          digest.digest().toHex() != release.manifest.artifactSha256) {
        throw OtaVerificationException("Downloaded bundle hash or size does not match the signed manifest")
      }
      return archive
    } catch (exception: Exception) {
      archive.delete()
      throw exception
    } finally {
      connection.disconnect()
    }
  }

  private fun openConnection(url: URL): HttpURLConnection {
    return (url.openConnection() as HttpURLConnection).apply {
      connectTimeout = CONNECT_TIMEOUT_MILLIS
      readTimeout = READ_TIMEOUT_MILLIS
      instanceFollowRedirects = false
      useCaches = false
    }
  }

  private fun validateDownloadUrl(downloadUrl: String) {
    val base = Uri.parse(config.serverUrl)
    val download = Uri.parse(downloadUrl)
    val basePort = effectivePort(base)
    if (download.userInfo != null || download.host != base.host || effectivePort(download) != basePort) {
      throw OtaVerificationException("Bundle URL must use the configured OTA server origin")
    }
    if (download.scheme != base.scheme) {
      throw OtaVerificationException("Bundle URL scheme does not match the configured OTA server")
    }
    if (download.scheme != "https" && !isLocalDevelopmentHttpAllowed(download)) {
      throw OtaVerificationException("Release APKs require HTTPS for OTA downloads")
    }
  }

  private fun supportsConfiguredTransport(): Boolean =
      Uri.parse(config.serverUrl).scheme == "https" ||
          isLocalDevelopmentHttpAllowed(Uri.parse(config.serverUrl))

  private fun isLocalDevelopmentHttpAllowed(uri: Uri): Boolean =
      config.allowInsecureHttp &&
          uri.scheme == "http" &&
          uri.host?.lowercase() in setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")

  private fun effectivePort(uri: Uri): Int =
      if (uri.port != -1) uri.port else if (uri.scheme == "https") 443 else 80

  private fun readActivationState(): OtaActivationState =
      OtaActivationState(
          activeReleaseId = preferences.getString(KEY_ACTIVE_RELEASE_ID, null),
          activeVersion = preferences.getInt(KEY_ACTIVE_VERSION, config.baseVersion),
          pendingReleaseId = preferences.getString(KEY_PENDING_RELEASE_ID, null),
          pendingVersion = preferences.getInt(KEY_PENDING_VERSION, -1).takeIf { it >= 0 },
          previousReleaseId = preferences.getString(KEY_PREVIOUS_RELEASE_ID, null),
          previousVersion = preferences.getInt(KEY_PREVIOUS_VERSION, -1).takeIf { it >= 0 },
          pendingLaunchAttempted = preferences.getBoolean(KEY_PENDING_LAUNCH_ATTEMPTED, false),
          failedReleaseId = preferences.getString(KEY_FAILED_RELEASE_ID, null),
      )

  private fun persistActivationState(state: OtaActivationState) {
    val editor =
        preferences.edit()
            .putString(KEY_RUNTIME_VERSION, config.runtimeVersion)
            .putInt(KEY_ACTIVE_VERSION, state.activeVersion)
            .putBoolean(KEY_PENDING_LAUNCH_ATTEMPTED, state.pendingLaunchAttempted)
    editor.putNullableString(KEY_ACTIVE_RELEASE_ID, state.activeReleaseId)
    editor.putNullableString(KEY_PENDING_RELEASE_ID, state.pendingReleaseId)
    editor.putNullableInt(KEY_PENDING_VERSION, state.pendingVersion)
    editor.putNullableString(KEY_PREVIOUS_RELEASE_ID, state.previousReleaseId)
    editor.putNullableInt(KEY_PREVIOUS_VERSION, state.previousVersion)
    editor.putNullableString(KEY_FAILED_RELEASE_ID, state.failedReleaseId)
    check(editor.commit()) { "Could not save OTA activation state" }
  }

  private fun bundleFile(releaseId: String): File =
      File(OtaBundleInstaller.releaseDirectory(updatesDirectory, releaseId), "index.android.bundle")

  private fun result(
      status: String,
      message: String,
      pendingVersion: Int? = null,
      action: String? = null,
  ): Map<String, Any?> =
      status().toMutableMap().apply {
        put("status", status)
        put("message", message)
        put("updateAvailable", status == "restart_required")
        put("action", action)
        if (pendingVersion != null) put("pendingVersion", pendingVersion)
      }

  private fun SharedPreferences.Editor.putNullableString(key: String, value: String?) =
      if (value == null) remove(key) else putString(key, value)

  private fun SharedPreferences.Editor.putNullableInt(key: String, value: Int?) =
      if (value == null) remove(key) else putInt(key, value)

  private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }

  companion object {
    private const val TAG = "OTAUpdates"
    private const val PREFERENCES_NAME = "ota_update_state"
    private const val KEY_RUNTIME_VERSION = "runtime_version"
    private const val KEY_INSTALLATION_ID = "installation_id"
    private const val KEY_ACTIVE_RELEASE_ID = "active_release_id"
    private const val KEY_ACTIVE_VERSION = "active_version"
    private const val KEY_PENDING_RELEASE_ID = "pending_release_id"
    private const val KEY_PENDING_VERSION = "pending_version"
    private const val KEY_PREVIOUS_RELEASE_ID = "previous_release_id"
    private const val KEY_PREVIOUS_VERSION = "previous_version"
    private const val KEY_PENDING_LAUNCH_ATTEMPTED = "pending_launch_attempted"
    private const val KEY_FAILED_RELEASE_ID = "failed_release_id"
    private const val CONNECT_TIMEOUT_MILLIS = 10_000
    private const val READ_TIMEOUT_MILLIS = 30_000
    private const val MAX_ARTIFACT_BYTES = 100L * 1024L * 1024L
  }
}
