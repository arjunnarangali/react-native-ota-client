package com.otasdk

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.regex.Pattern

internal data class OtaReleaseManifest(
    val releaseId: String,
    val platform: String,
    val appId: String,
    val runtimeVersion: String,
    val version: Int,
    val artifactSha256: String,
    val artifactSizeBytes: Long,
    val createdAt: String,
    val manifestVersion: Int = 1,
) {
  /** Must match the Python publisher's sorted-key, compact UTF-8 JSON encoding exactly. */
  fun canonicalBytes(): ByteArray {
    val json =
        "{" +
            "\"app_id\":${quote(appId)}," +
            "\"artifact_sha256\":${quote(artifactSha256)}," +
            "\"artifact_size_bytes\":$artifactSizeBytes," +
            "\"created_at\":${quote(createdAt)}," +
            "\"manifest_version\":$manifestVersion," +
            "\"platform\":${quote(platform)}," +
            "\"release_id\":${quote(releaseId)}," +
            "\"runtime_version\":${quote(runtimeVersion)}," +
            "\"version\":$version" +
            "}"
    return json.toByteArray(StandardCharsets.UTF_8)
  }

  companion object {
    private val appIdPattern = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val runtimePattern = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    private val sha256Pattern = Pattern.compile("^[a-f0-9]{64}$")
    private const val MAX_ARTIFACT_BYTES = 100L * 1024L * 1024L

    fun parse(json: JSONObject): OtaReleaseManifest {
      val manifest =
          OtaReleaseManifest(
              manifestVersion = json.getInt("manifest_version"),
              releaseId = json.getString("release_id"),
              platform = json.getString("platform"),
              appId = json.getString("app_id"),
              runtimeVersion = json.getString("runtime_version"),
              version = json.getInt("version"),
              artifactSha256 = json.getString("artifact_sha256"),
              artifactSizeBytes = json.getLong("artifact_size_bytes"),
              createdAt = json.getString("created_at"),
          )
      manifest.validate()
      return manifest
    }

    private fun quote(value: String): String {
      val output = StringBuilder(value.length + 2).append('"')
      value.forEach { character ->
        when (character) {
          '\\' -> output.append("\\\\")
          '"' -> output.append("\\\"")
          '\b' -> output.append("\\b")
          '\u000C' -> output.append("\\f")
          '\n' -> output.append("\\n")
          '\r' -> output.append("\\r")
          '\t' -> output.append("\\t")
          else -> {
            if (character.code < 0x20) {
              output.append("\\u%04x".format(character.code))
            } else {
              output.append(character)
            }
          }
        }
      }
      return output.append('"').toString()
    }
  }

  fun verify(signatureBase64: String, publicKeyPem: ByteArray) {
    val pem = String(publicKeyPem, StandardCharsets.US_ASCII)
    val encodedKey =
        pem.lineSequence()
            .filterNot { it.startsWith("-----") }
            .joinToString(separator = "")
    val keyBytes = Base64.decode(encodedKey, Base64.DEFAULT)
    val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
    verifySignature(Base64.decode(signatureBase64, Base64.DEFAULT), publicKey)
  }

  fun verifySignature(signatureBytes: ByteArray, publicKey: PublicKey) {
    val verifier = Signature.getInstance("SHA256withECDSA")
    verifier.initVerify(publicKey)
    verifier.update(canonicalBytes())
    if (!verifier.verify(signatureBytes)) {
      throw OtaVerificationException("Release manifest signature is invalid")
    }
  }

  private fun validate() {
    if (manifestVersion != 1) throw OtaVerificationException("Unsupported release manifest version")
    try {
      UUID.fromString(releaseId)
    } catch (exception: IllegalArgumentException) {
      throw OtaVerificationException("Release ID is invalid")
    }
    if (platform != "android") throw OtaVerificationException("Release platform is not Android")
    if (!appIdPattern.matcher(appId).matches()) throw OtaVerificationException("App ID is invalid")
    if (!runtimePattern.matcher(runtimeVersion).matches()) {
      throw OtaVerificationException("Runtime version is invalid")
    }
    if (version <= 0) throw OtaVerificationException("Release version must be positive")
    if (!sha256Pattern.matcher(artifactSha256).matches()) {
      throw OtaVerificationException("Artifact hash is invalid")
    }
    if (artifactSizeBytes !in 1..MAX_ARTIFACT_BYTES) {
      throw OtaVerificationException("Artifact size is outside the allowed range")
    }
    if (createdAt.isBlank() || createdAt.length > 64) {
      throw OtaVerificationException("Release creation time is invalid")
    }
  }
}

internal data class VerifiedOtaRelease(
    val manifest: OtaReleaseManifest,
    val signature: String,
    val channel: String,
    val rolloutPercentage: Int,
    val action: String,
    val downloadUrl: String,
)

internal class OtaVerificationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

internal object OtaReleaseVerifier {
  fun verify(
      releaseJson: JSONObject,
      publicKeyPem: ByteArray,
      expectedAppId: String,
      expectedRuntimeVersion: String,
      expectedChannel: String,
      currentVersion: Int,
  ): VerifiedOtaRelease {
    try {
      val manifest = OtaReleaseManifest.parse(releaseJson.getJSONObject("manifest"))
      val signature = releaseJson.getString("signature")
      val channel = releaseJson.getString("channel")
      val rollout = releaseJson.getInt("rollout_percentage")
      val action = releaseJson.getString("action")
      val downloadUrl = releaseJson.getString("download_url")

      if (manifest.appId != expectedAppId) throw OtaVerificationException("App ID does not match this APK")
      if (manifest.runtimeVersion != expectedRuntimeVersion) {
        throw OtaVerificationException("Release targets a different native runtime")
      }
      if (channel != expectedChannel) throw OtaVerificationException("Release channel does not match")
      if (rollout !in 0..100) throw OtaVerificationException("Rollout percentage is invalid")
      when (action) {
        "update" -> if (manifest.version <= currentVersion) {
          throw OtaVerificationException("Update version is not newer than the installed version")
        }
        "rollback" -> if (manifest.version >= currentVersion) {
          throw OtaVerificationException("Rollback version is not older than the installed version")
        }
        else -> throw OtaVerificationException("Update action is invalid")
      }
      if (downloadUrl.isBlank()) throw OtaVerificationException("Bundle URL is missing")

      manifest.verify(signature, publicKeyPem)
      return VerifiedOtaRelease(manifest, signature, channel, rollout, action, downloadUrl)
    } catch (exception: OtaVerificationException) {
      throw exception
    } catch (exception: Exception) {
      throw OtaVerificationException("Release response is malformed", exception)
    }
  }
}
