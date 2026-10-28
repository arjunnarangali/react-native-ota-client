package com.otasdk

import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Enumeration
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

internal object OtaBundleInstaller {
  private const val ENTRYPOINT = "index.android.bundle"
  private const val PACKAGE_MANIFEST = "ota-manifest.json"
  private const val MAX_PACKAGE_MANIFEST_BYTES = 1024L * 1024L
  private const val MAX_UNCOMPRESSED_BYTES = 500L * 1024L * 1024L
  private const val MAX_ARCHIVE_ENTRIES = 10_000

  fun releaseDirectory(updatesDirectory: File, releaseId: String): File =
      File(updatesDirectory, releaseId)

  fun verifyExisting(
      releaseDirectory: File,
      release: VerifiedOtaRelease,
  ): Boolean {
    return try {
      if (!releaseDirectory.isDirectory) return false
      if (!File(releaseDirectory, "release-manifest.json").readBytes().contentEquals(
          release.manifest.canonicalBytes()
      )) return false
      if (File(releaseDirectory, "release-signature.txt").readText(Charsets.US_ASCII) != release.signature) {
        return false
      }
      val internalManifest = JSONObject(File(releaseDirectory, PACKAGE_MANIFEST).readText(Charsets.UTF_8))
      validateExtractedFiles(releaseDirectory, internalManifest)
    } catch (_: Exception) {
      false
    }
  }

  fun install(
      archiveFile: File,
      updatesDirectory: File,
      release: VerifiedOtaRelease,
  ): File {
    val finalDirectory = releaseDirectory(updatesDirectory, release.manifest.releaseId)
    if (finalDirectory.exists()) {
      if (verifyExisting(finalDirectory, release)) return finalDirectory
      throw OtaVerificationException("A stored release with this ID failed local verification")
    }

    updatesDirectory.mkdirs()
    val stagingDirectory = File(updatesDirectory, ".${release.manifest.releaseId}.${java.util.UUID.randomUUID()}.staging")
    if (!stagingDirectory.mkdirs()) throw OtaVerificationException("Cannot create update staging directory")

    try {
      val internalManifest = extractAndValidate(archiveFile, stagingDirectory)
      File(stagingDirectory, "release-manifest.json").writeBytes(release.manifest.canonicalBytes())
      File(stagingDirectory, "release-signature.txt").writeText(release.signature, Charsets.US_ASCII)

      if (finalDirectory.exists()) {
        if (verifyExisting(finalDirectory, release)) return finalDirectory
        throw OtaVerificationException("A stored release with this ID failed local verification")
      }
      if (!stagingDirectory.renameTo(finalDirectory)) {
        throw OtaVerificationException("Cannot atomically activate the staged bundle files")
      }
      if (!validateExtractedFiles(finalDirectory, internalManifest)) {
        finalDirectory.deleteRecursively()
        throw OtaVerificationException("Installed bundle failed its final integrity check")
      }
      return finalDirectory
    } catch (exception: Exception) {
      stagingDirectory.deleteRecursively()
      throw exception
    }
  }

  private fun extractAndValidate(archiveFile: File, destination: File): JSONObject {
    ZipFile(archiveFile).use { archive ->
      val entries = entriesAsList(archive.entries())
      if (entries.size !in 2..MAX_ARCHIVE_ENTRIES) {
        throw OtaVerificationException("Bundle ZIP has an invalid number of entries")
      }
      if (entries.any { it.size < 0L }) {
        throw OtaVerificationException("Bundle ZIP contains an entry with unknown size")
      }
      val names = entries.map { it.name }
      if (names.size != names.toSet().size) throw OtaVerificationException("Bundle ZIP has duplicate entries")
      entries.forEach { entry ->
        if (entry.isDirectory || !isSafeRelativePath(entry.name)) {
          throw OtaVerificationException("Bundle ZIP contains an unsafe path")
        }
      }
      if (entries.sumOf { it.size.coerceAtLeast(0L) } > MAX_UNCOMPRESSED_BYTES) {
        throw OtaVerificationException("Bundle ZIP expands beyond the allowed size")
      }

      val packageManifestEntry = archive.getEntry(PACKAGE_MANIFEST)
          ?: throw OtaVerificationException("Bundle package manifest is missing")
      if (packageManifestEntry.size !in 1..MAX_PACKAGE_MANIFEST_BYTES) {
        throw OtaVerificationException("Bundle package manifest has an invalid size")
      }
      val packageManifestBytes = archive.getInputStream(packageManifestEntry).use { it.readBytes() }
      val packageManifest = JSONObject(String(packageManifestBytes, Charsets.UTF_8))
      val expectedFiles = expectedPackageFiles(packageManifest)
      if (expectedFiles != names.toSet()) {
        throw OtaVerificationException("Bundle ZIP does not match its internal package manifest")
      }
      if (entries.sumOf { it.size } > MAX_UNCOMPRESSED_BYTES) {
        throw OtaVerificationException("Bundle ZIP expands beyond the allowed size")
      }

      entries.filter { it.name != PACKAGE_MANIFEST }.forEach { entry ->
        val targetRelativePath =
            if (entry.name == ENTRYPOINT) ENTRYPOINT else entry.name.removePrefix("assets/")
        val output = safeOutputFile(destination, targetRelativePath)
        output.parentFile?.mkdirs()
        if (!output.createNewFile()) throw OtaVerificationException("Duplicate package output path")
        val expectedHash =
            if (entry.name == ENTRYPOINT) {
              packageManifest.getString("entrypoint_sha256")
            } else {
              assetForPath(packageManifest.getJSONArray("assets"), entry.name).getString("sha256")
            }
        val expectedSize =
            if (entry.name == ENTRYPOINT) {
              entry.size
            } else {
              assetForPath(packageManifest.getJSONArray("assets"), entry.name).getLong("size_bytes")
            }
        copyAndVerify(archive.getInputStream(entry), output, expectedHash, expectedSize)
      }

      File(destination, PACKAGE_MANIFEST).writeBytes(packageManifestBytes)
      if (!validateExtractedFiles(destination, packageManifest)) {
        throw OtaVerificationException("Bundle package contents failed integrity verification")
      }
      return packageManifest
    }
  }

  private fun expectedPackageFiles(packageManifest: JSONObject): Set<String> {
    if (packageManifest.optInt("format_version") != 1) {
      throw OtaVerificationException("Unsupported bundle package format")
    }
    if (packageManifest.optString("entrypoint") != ENTRYPOINT ||
        packageManifest.optString("entrypoint_format") != "hermes-bytecode") {
      throw OtaVerificationException("Bundle package must contain the Android Hermes bytecode entrypoint")
    }
    val entrypointHash = packageManifest.optString("entrypoint_sha256")
    if (!isSha256(entrypointHash)) throw OtaVerificationException("Bundle entrypoint hash is invalid")

    val expected = mutableSetOf(PACKAGE_MANIFEST, ENTRYPOINT)
    val assets = packageManifest.optJSONArray("assets")
        ?: throw OtaVerificationException("Bundle asset manifest is missing")
    for (index in 0 until assets.length()) {
      val asset = assets.optJSONObject(index)
          ?: throw OtaVerificationException("Bundle asset metadata is invalid")
      val path = asset.optString("path")
      if (!isSafeAssetPath(path) || !isSha256(asset.optString("sha256"))) {
        throw OtaVerificationException("Bundle asset metadata is invalid")
      }
      if (asset.optLong("size_bytes", -1L) < 0L || !expected.add(path)) {
        throw OtaVerificationException("Bundle asset metadata contains duplicate or invalid entries")
      }
    }
    return expected
  }

  private fun validateExtractedFiles(directory: File, manifest: JSONObject): Boolean {
    val bundle = File(directory, ENTRYPOINT)
    if (!bundle.isFile || !bundleHasHermesBytecodeHeader(bundle)) return false
    if (sha256(bundle) != manifest.optString("entrypoint_sha256")) return false

    val assets = manifest.optJSONArray("assets") ?: return false
    for (index in 0 until assets.length()) {
      val asset = assets.optJSONObject(index) ?: return false
      val zipPath = asset.optString("path")
      if (!isSafeAssetPath(zipPath)) return false
      val localPath = zipPath.removePrefix("assets/")
      val assetFile = safeOutputFile(directory, localPath)
      if (!assetFile.isFile || assetFile.length() != asset.optLong("size_bytes", -1L)) return false
      if (sha256(assetFile) != asset.optString("sha256")) return false
    }
    return true
  }

  private fun copyAndVerify(
      input: java.io.InputStream,
      output: File,
      expectedSha256: String,
      expectedSize: Long,
  ) {
    val digest = MessageDigest.getInstance("SHA-256")
    var total = 0L
    input.use { source ->
      BufferedInputStream(source).use { buffered ->
        FileOutputStream(output).use { destination ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          while (true) {
            val count = buffered.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_UNCOMPRESSED_BYTES) {
              throw OtaVerificationException("Bundle entry exceeds the allowed size")
            }
            digest.update(buffer, 0, count)
            destination.write(buffer, 0, count)
          }
          destination.fd.sync()
        }
      }
    }
    if (total != expectedSize || digest.digest().toHex() != expectedSha256) {
      throw OtaVerificationException("Bundle entry hash or size does not match package manifest")
    }
    if (output.name == ENTRYPOINT && !bundleHasHermesBytecodeHeader(output)) {
      throw OtaVerificationException("Bundle entrypoint is not Hermes bytecode")
    }
  }

  private fun assetForPath(assets: JSONArray, path: String): JSONObject {
    for (index in 0 until assets.length()) {
      val item = assets.getJSONObject(index)
      if (item.getString("path") == path) return item
    }
    throw OtaVerificationException("Bundle ZIP contains an undeclared asset")
  }

  private fun entriesAsList(entries: Enumeration<out ZipEntry>): List<ZipEntry> =
      buildList {
        while (entries.hasMoreElements()) add(entries.nextElement())
      }

  private fun safeOutputFile(root: File, relativePath: String): File {
    if (!isSafeRelativePath(relativePath)) throw OtaVerificationException("Bundle path is unsafe")
    val rootPath = root.canonicalPath + File.separator
    val file = File(root, relativePath)
    if (!file.canonicalPath.startsWith(rootPath)) throw OtaVerificationException("Bundle path escaped staging directory")
    return file
  }

  private fun isSafeRelativePath(path: String): Boolean {
    if (path.isBlank() || path.startsWith('/') || path.contains('\\') || path.contains(':')) return false
    return path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
  }

  private fun isSafeAssetPath(path: String): Boolean {
    if (!path.startsWith("assets/") || !isSafeRelativePath(path)) return false
    return path.removePrefix("assets/") !in
        setOf(ENTRYPOINT, PACKAGE_MANIFEST, "release-manifest.json", "release-signature.txt")
  }

  private fun bundleHasHermesBytecodeHeader(file: File): Boolean {
    if (file.length() < 4L) return false
    return file.inputStream().use { input ->
      input.read() == 0xC6 && input.read() == 0x1F && input.read() == 0xBC && input.read() == 0x03
    }
  }

  private fun isSha256(value: String): Boolean = value.matches(Regex("^[a-f0-9]{64}$"))

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
      }
    }
    return digest.digest().toHex()
  }

  private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(Locale.ROOT, byte) }
}
