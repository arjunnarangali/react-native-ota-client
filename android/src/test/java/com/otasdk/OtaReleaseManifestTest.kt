package com.otasdk

import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OtaReleaseManifestTest {
  private val manifest =
      OtaReleaseManifest(
          releaseId = "519b9918-f1e8-4bcf-9319-19c54389e575",
          platform = "android",
          appId = "com.ota",
          runtimeVersion = "rn-0.88.0-android-v1",
          version = 3,
          artifactSha256 = "a".repeat(64),
          artifactSizeBytes = 1234,
          createdAt = "2026-10-22T10:25:36.458384Z",
      )

  @Test
  fun canonicalManifestUsesPublisherKeyOrderingAndCompactEncoding() {
    val expected =
        "{\"app_id\":\"com.ota\",\"artifact_sha256\":\"${"a".repeat(64)}\"," +
            "\"artifact_size_bytes\":1234,\"created_at\":\"2026-10-22T10:25:36.458384Z\"," +
            "\"manifest_version\":1,\"platform\":\"android\"," +
            "\"release_id\":\"519b9918-f1e8-4bcf-9319-19c54389e575\"," +
            "\"runtime_version\":\"rn-0.88.0-android-v1\",\"version\":3}"

    assertArrayEquals(expected.toByteArray(StandardCharsets.UTF_8), manifest.canonicalBytes())
  }

  @Test
  fun p256SignatureVerifiesAndDetectsManifestTampering() {
    val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    val signer = Signature.getInstance("SHA256withECDSA")
    signer.initSign(keyPair.private)
    signer.update(manifest.canonicalBytes())
    val signature = signer.sign()

    manifest.verifySignature(signature, keyPair.public)
    assertThrows(OtaVerificationException::class.java) {
      manifest.copy(version = 4).verifySignature(signature, keyPair.public)
    }
  }
}
