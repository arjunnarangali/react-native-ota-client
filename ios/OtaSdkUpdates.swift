import CryptoKit
import Foundation
import Security
import ZIPFoundation

private struct OtaConfiguration {
  let serverURL: URL
  let runtimeVersion: String
  let channel: String
  let baseVersion: Int
  let publicKeyPEM: Data
  let isDebugBuild: Bool
  let appId: String
  let allowLocalHTTP: Bool
}

private struct OtaRelease {
  let id: String
  let manifest: [String: Any]
  let signature: String
  let version: Int
  let action: String
  let downloadURL: URL
  let artifactHash: String
  let artifactSize: Int
}

/** Public iOS host-app API. Call initialize() and beginProcess() before React Native starts. */
@objc(OtaSdkUpdates)
public final class OtaSdkUpdates: NSObject {
  private static let shared = OtaSdkUpdates()
  private let defaults = UserDefaults.standard
  private let lock = NSRecursiveLock()
  private var configuration: OtaConfiguration?
  private var selectedBundleURL: URL?
  private var checkInProgress = false
  private let rootDirectory: URL = {
    FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("OtaSdk", isDirectory: true)
  }()

  private enum Key {
    static let runtime = "ota.runtime"
    static let installationId = "ota.installationId"
    static let activeRelease = "ota.activeRelease"
    static let activeVersion = "ota.activeVersion"
    static let pendingRelease = "ota.pendingRelease"
    static let pendingVersion = "ota.pendingVersion"
    static let previousRelease = "ota.previousRelease"
    static let previousVersion = "ota.previousVersion"
    static let pendingAttempted = "ota.pendingLaunchAttempted"
    static let failedRelease = "ota.failedRelease"
  }

  @objc(initializeWithServerUrl:runtimeVersion:channel:baseVersion:publicKeyPem:isDebugBuild:appId:allowLocalHttp:)
  public static func initialize(
    serverUrl: String,
    runtimeVersion: String,
    channel: String,
    baseVersion: NSNumber,
    publicKeyPem: String,
    isDebugBuild: Bool,
    appId: String,
    allowLocalHttp: Bool
  ) {
    shared.configure(
      serverUrl: serverUrl,
      runtimeVersion: runtimeVersion,
      channel: channel,
      baseVersion: baseVersion.intValue,
      publicKeyPEM: Data(publicKeyPem.utf8),
      isDebugBuild: isDebugBuild,
      appId: appId,
      allowLocalHTTP: allowLocalHttp
    )
  }

  @objc public static func beginProcess() { shared.beginProcess() }

  @objc public static func bundleURL() -> URL? {
    shared.lock.lock()
    defer { shared.lock.unlock() }
    return shared.selectedBundleURL
  }

  @objc public static func status() -> NSDictionary { shared.statusDictionary() as NSDictionary }
  @objc public static func hasPendingUpdate() -> Bool {
    shared.lock.lock()
    defer { shared.lock.unlock() }
    return shared.defaults.string(forKey: Key.pendingRelease) != nil
  }

  @objc(checkForUpdateWithCompletion:)
  public static func checkForUpdate(completion: @escaping (NSDictionary?, NSError?) -> Void) {
    Task {
      do { completion(try await shared.checkForUpdate() as NSDictionary, nil) }
      catch { completion(nil, error as NSError) }
    }
  }

  @objc(markUpdateSuccessfulWithCompletion:)
  public static func markUpdateSuccessful(completion: @escaping (NSDictionary?, NSError?) -> Void) {
    do { completion(try shared.markHealthy() as NSDictionary, nil) }
    catch { completion(nil, error as NSError) }
  }

  private func configure(
    serverUrl: String, runtimeVersion: String, channel: String, baseVersion: Int,
    publicKeyPEM: Data, isDebugBuild: Bool, appId: String, allowLocalHTTP: Bool
  ) {
    lock.lock()
    defer { lock.unlock() }
    guard let url = URL(string: serverUrl), url.host != nil, url.user == nil, url.password == nil,
          ["https", "http"].contains(url.scheme?.lowercased() ?? ""),
          !runtimeVersion.isEmpty, !channel.isEmpty, !appId.isEmpty, !publicKeyPEM.isEmpty,
          baseVersion >= 0 else {
      NSLog("[OtaSdkUpdates] Invalid iOS OTA configuration")
      configuration = nil
      return
    }
    configuration = OtaConfiguration(
      serverURL: url, runtimeVersion: runtimeVersion, channel: channel, baseVersion: baseVersion,
      publicKeyPEM: publicKeyPEM, isDebugBuild: isDebugBuild, appId: appId,
      allowLocalHTTP: allowLocalHTTP
    )
  }

  private func beginProcess() {
    lock.lock()
    defer { lock.unlock() }
    guard let configuration else { return }
    selectedBundleURL = nil
    guard !configuration.isDebugBuild else { return }
    if let runtime = defaults.string(forKey: Key.runtime), runtime != configuration.runtimeVersion {
      defaults.set(configuration.runtimeVersion, forKey: Key.runtime)
      clearActivationState()
      try? FileManager.default.removeItem(at: updatesDirectory)
    } else {
      defaults.set(configuration.runtimeVersion, forKey: Key.runtime)
    }
    try? FileManager.default.createDirectory(at: rootDirectory, withIntermediateDirectories: true)
    try? FileManager.default.removeItem(at: stagingDirectory)

    let startup = OtaActivationPolicy.beginStartup(readActivationState(baseVersion: configuration.baseVersion))
    persistActivationState(startup.state)
    if let releaseId = startup.selectedReleaseId,
       let candidate = verifiedBundleURL(for: releaseId, configuration: configuration) {
      selectedBundleURL = candidate
    } else if let releaseId = startup.selectedReleaseId {
      if startup.state.pendingReleaseId == releaseId {
        let rollback = OtaActivationPolicy.beginStartup(startup.state)
        persistActivationState(rollback.state)
        selectedBundleURL = rollback.selectedReleaseId.flatMap { verifiedBundleURL(for: $0, configuration: configuration) }
      } else {
        var fallback = startup.state
        fallback.activeReleaseId = nil
        fallback.activeVersion = configuration.baseVersion
        persistActivationState(fallback)
      }
    }
  }

  private var updatesDirectory: URL { rootDirectory.appendingPathComponent("updates", isDirectory: true) }
  private var stagingDirectory: URL { rootDirectory.appendingPathComponent("staging", isDirectory: true) }

  private func restorePreviousBundle(configuration: OtaConfiguration) {
    let previous = defaults.string(forKey: Key.previousRelease)
    let previousURL = previous.flatMap { verifiedBundleURL(for: $0, configuration: configuration) }
    defaults.set(previousURL == nil ? nil : previous, forKey: Key.activeRelease)
    defaults.set(previousURL == nil ? configuration.baseVersion : (defaults.object(forKey: Key.previousVersion) as? Int ?? configuration.baseVersion), forKey: Key.activeVersion)
    clearPendingState()
    selectedBundleURL = previousURL
  }

  private func checkForUpdate() async throws -> [String: Any] {
    let configuration: OtaConfiguration
    switch try beginCheck() {
    case .finished(let result): return result
    case .proceed(let configured): configuration = configured
    }
    defer { finishCheck() }

    var request = URLRequest(url: configuration.serverURL.appendingPathComponent("api/v1/updates/check"))
    request.httpMethod = "POST"
    request.timeoutInterval = 20
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    request.setValue("application/json", forHTTPHeaderField: "Accept")
    let installationId = defaults.string(forKey: Key.installationId) ?? UUID().uuidString.lowercased()
    defaults.set(installationId, forKey: Key.installationId)
    request.httpBody = try JSONSerialization.data(withJSONObject: [
      "platform": "ios", "app_id": configuration.appId,
      "runtime_version": configuration.runtimeVersion, "channel": configuration.channel,
      "current_version": defaults.object(forKey: Key.activeVersion) as? Int ?? configuration.baseVersion,
      "installation_id": installationId,
      "failed_release_id": defaults.string(forKey: Key.failedRelease) as Any? ?? NSNull(),
    ], options: [.sortedKeys])

    let session = URLSession(configuration: .ephemeral, delegate: OtaRedirectBlocker(), delegateQueue: nil)
    defer { session.invalidateAndCancel() }
    let (data, response) = try await session.data(for: request)
    guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
      throw otaError("OTA server returned an unsuccessful response")
    }
    guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
          let available = json["update_available"] as? Bool else { throw otaError("OTA server response is malformed") }
    guard available else { return result(status: "up_to_date", message: "No compatible update is available.") }
    guard let releaseJSON = json["release"] as? [String: Any] else { throw otaError("OTA release response is missing metadata") }
    let release = try verifyRelease(releaseJSON, configuration: configuration,
                                    currentVersion: defaults.object(forKey: Key.activeVersion) as? Int ?? configuration.baseVersion)
    let archive = try await download(release, configuration: configuration)
    defer { try? FileManager.default.removeItem(at: archive) }
    try install(archive: archive, release: release, configuration: configuration)
    return stage(release, baseVersion: configuration.baseVersion)
  }

  private func markHealthy() throws -> [String: Any] {
    lock.lock()
    defer { lock.unlock() }
    guard let configuration else { throw otaError("OTA SDK has not been initialized") }
    if configuration.isDebugBuild { return statusDictionary() }
    persistActivationState(OtaActivationPolicy.markHealthy(readActivationState(baseVersion: configuration.baseVersion)))
    return statusDictionary()
  }

  private func stage(_ release: OtaRelease, baseVersion: Int) -> [String: Any] {
    lock.lock()
    defer { lock.unlock() }
    persistActivationState(OtaActivationPolicy.stage(readActivationState(baseVersion: baseVersion), releaseId: release.id, version: release.version))
    return result(status: "restart_required", message: "Verified release \(release.version) is ready. Close and reopen the app to apply it.", pendingVersion: release.version, action: release.action)
  }

  private func statusDictionary() -> [String: Any] {
    lock.lock()
    defer { lock.unlock() }
    guard let configuration else {
      return [
        "enabled": false, "status": "not_initialized", "message": "OTA SDK has not been initialized.",
        "runtimeVersion": "", "channel": "", "currentVersion": 0,
        "pendingVersion": NSNull(), "pendingRestart": false, "updateAvailable": false, "action": NSNull(),
      ]
    }
    let debug = configuration.isDebugBuild
    let pending = defaults.string(forKey: Key.pendingRelease) != nil
    return [
      "enabled": !debug, "status": debug ? "debug_build" : (pending ? "restart_required" : "ready"),
      "message": debug ? "OTA activation is disabled in debug builds; Metro supplies JavaScript." : (pending ? "A verified update is ready. Close and reopen the app to apply it." : "This app is using its active bundle."),
      "runtimeVersion": configuration.runtimeVersion, "channel": configuration.channel,
      "currentVersion": defaults.object(forKey: Key.activeVersion) as? Int ?? configuration.baseVersion,
      "pendingVersion": defaults.object(forKey: Key.pendingVersion) ?? NSNull(),
      "pendingRestart": pending, "updateAvailable": pending, "action": NSNull(),
    ]
  }

  private func result(status: String, message: String, pendingVersion: Int? = nil, action: String? = nil) -> [String: Any] {
    var value = statusDictionary()
    value["status"] = status
    value["message"] = message
    value["updateAvailable"] = status == "restart_required"
    value["action"] = action as Any? ?? NSNull()
    if let pendingVersion { value["pendingVersion"] = pendingVersion }
    return value
  }

  private func verifyRelease(_ json: [String: Any], configuration: OtaConfiguration, currentVersion: Int) throws -> OtaRelease {
    guard let manifest = json["manifest"] as? [String: Any], let signature = json["signature"] as? String,
          let action = json["action"] as? String, let channel = json["channel"] as? String,
          let downloadString = json["download_url"] as? String, let downloadURL = URL(string: downloadString),
          let id = manifest["release_id"] as? String, let platform = manifest["platform"] as? String,
          let appId = manifest["app_id"] as? String, let runtime = manifest["runtime_version"] as? String,
          let version = manifest["version"] as? Int, let artifactHash = manifest["artifact_sha256"] as? String,
          let artifactSize = manifest["artifact_size_bytes"] as? Int, let createdAt = manifest["created_at"] as? String,
          let manifestVersion = manifest["manifest_version"] as? Int,
          let rollout = json["rollout_percentage"] as? Int else { throw otaError("Release response is malformed") }
    guard UUID(uuidString: id) != nil, manifestVersion == 1, platform == "ios", appId == configuration.appId,
          runtime == configuration.runtimeVersion,
          appId.range(of: "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$", options: .regularExpression) != nil,
          runtime.range(of: "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$", options: .regularExpression) != nil,
          version > 0, artifactHash.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
          (1...(100 * 1024 * 1024)).contains(artifactSize), !createdAt.isEmpty, createdAt.count <= 64 else {
      throw otaError("Release manifest does not match this iOS runtime")
    }
    guard (action == "update" && version > currentVersion) || (action == "rollback" && version < currentVersion) else {
      throw otaError("Release version is incompatible with the current app version")
    }
    guard channel == configuration.channel, (0...100).contains(rollout), sameOrigin(downloadURL, configuration.serverURL),
          supportsDownload(downloadURL, configuration: configuration) else { throw otaError("Bundle URL origin or channel is invalid") }
    guard verifySignature(canonicalManifest(manifest), signature: signature, pem: configuration.publicKeyPEM) else {
      throw otaError("Release manifest signature is invalid")
    }
    return OtaRelease(id: id, manifest: manifest, signature: signature, version: version, action: action,
                      downloadURL: downloadURL, artifactHash: artifactHash, artifactSize: artifactSize)
  }

  private func canonicalManifest(_ manifest: [String: Any]) -> Data {
    let keys = ["app_id", "artifact_sha256", "artifact_size_bytes", "created_at", "manifest_version", "platform", "release_id", "runtime_version", "version"]
    let values = keys.map { key -> String in
      let value = manifest[key]!
      let encoded: String
      if let string = value as? String,
         let data = try? JSONSerialization.data(withJSONObject: [string], options: [.fragmentsAllowed, .sortedKeys]),
         let json = String(data: data, encoding: .utf8) {
        encoded = String(json.dropFirst().dropLast())
      } else { encoded = String(describing: value) }
      return "\"\(key)\":\(encoded)"
    }
    return Data(("{" + values.joined(separator: ",") + "}").utf8)
  }

  private func verifySignature(_ message: Data, signature: String, pem: Data) -> Bool {
    guard let keyData = sec1PublicKey(from: pem),
          let key = SecKeyCreateWithData(keyData as CFData, [kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeyClass as String: kSecAttrKeyClassPublic, kSecAttrKeySizeInBits as String: 256] as CFDictionary, nil),
          let signatureData = Data(base64Encoded: signature) else { return false }
    return SecKeyVerifySignature(key, .ecdsaSignatureMessageX962SHA256, message as CFData, signatureData as CFData, nil)
  }

  private func sec1PublicKey(from pem: Data) -> Data? {
    let text = String(decoding: pem, as: UTF8.self)
    guard let der = Data(base64Encoded: text.components(separatedBy: .newlines).filter { !$0.hasPrefix("---") }.joined()) else { return nil }
    let marker = Data([0x03, 0x42, 0x00, 0x04])
    guard let range = der.firstRange(of: marker), range.lowerBound + 68 <= der.count else { return nil }
    return der.subdata(in: (range.lowerBound + 3)..<(range.lowerBound + 68))
  }

  private func download(_ release: OtaRelease, configuration: OtaConfiguration) async throws -> URL {
    var request = URLRequest(url: release.downloadURL)
    request.timeoutInterval = 40
    request.setValue("application/zip", forHTTPHeaderField: "Accept")
    let session = URLSession(configuration: .ephemeral, delegate: OtaRedirectBlocker(), delegateQueue: nil)
    defer { session.invalidateAndCancel() }
    let (temporaryURL, response) = try await session.download(for: request)
    guard let http = response as? HTTPURLResponse, http.statusCode == 200 else { throw otaError("Bundle server returned an unsuccessful response") }
    let size = try temporaryURL.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? -1
    guard size == release.artifactSize, size <= 100 * 1024 * 1024,
          try sha256(temporaryURL) == release.artifactHash else {
      throw otaError("Bundle hash or size does not match signed manifest")
    }
    try FileManager.default.createDirectory(at: stagingDirectory, withIntermediateDirectories: true)
    let archive = stagingDirectory.appendingPathComponent("\(release.id).zip.part")
    try? FileManager.default.removeItem(at: archive)
    try FileManager.default.moveItem(at: temporaryURL, to: archive)
    return archive
  }

  private func install(archive: URL, release: OtaRelease, configuration: OtaConfiguration) throws {
    let zip: Archive
    guard let opened = Archive(url: archive, accessMode: .read, preferredEncoding: nil) else {
      throw otaError("Downloaded OTA archive is invalid")
    }
    zip = opened
    let entries = Array(zip)
    guard (2...10_000).contains(entries.count) else { throw otaError("OTA archive has an invalid number of entries") }
    var expanded: Int64 = 0
    var byName: [String: Entry] = [:]
    for entry in entries {
      let size = Int64(entry.uncompressedSize)
      guard size >= 0, size <= 500 * 1024 * 1024 - expanded else { throw otaError("OTA archive exceeds package limits") }
      expanded += size
      guard safePath(entry.path), byName[entry.path] == nil, entry.type == .file else { throw otaError("OTA archive contains unsafe or duplicate paths") }
      byName[entry.path] = entry
    }
    guard let manifestEntry = byName["ota-manifest.json"], manifestEntry.uncompressedSize <= 1_048_576,
          let manifestData = try? zip.extractData(manifestEntry, maximumBytes: 1_048_576),
          let package = try JSONSerialization.jsonObject(with: manifestData) as? [String: Any],
          package["format_version"] as? Int == 1, package["entrypoint"] as? String == "main.jsbundle",
          package["entrypoint_format"] as? String == "hermes-bytecode",
          let entryHash = package["entrypoint_sha256"] as? String, let assets = package["assets"] as? [[String: Any]],
          let bundleEntry = byName["main.jsbundle"] else { throw otaError("iOS package manifest or entrypoint is invalid") }

    var declared = Set(["ota-manifest.json", "main.jsbundle"])
    var files: [(Entry, String, String, Int)] = [(bundleEntry, "main.jsbundle", entryHash, Int(bundleEntry.uncompressedSize))]
    for asset in assets {
      guard let path = asset["path"] as? String, path.hasPrefix("assets/"), safePath(path),
            let hash = asset["sha256"] as? String, let size = asset["size_bytes"] as? Int,
            let entry = byName[path], declared.insert(path).inserted,
            !["main.jsbundle", "ota-manifest.json", "release-manifest.json", "release-signature.txt"].contains(String(path.dropFirst(7))) else {
        throw otaError("iOS asset metadata is invalid")
      }
      files.append((entry, String(path.dropFirst(7)), hash, size))
    }
    guard declared == Set(byName.keys) else { throw otaError("OTA archive contains undeclared files") }
    try FileManager.default.createDirectory(at: updatesDirectory, withIntermediateDirectories: true)
    let staging = updatesDirectory.appendingPathComponent(".\(release.id).\(UUID().uuidString).staging", isDirectory: true)
    let final = updatesDirectory.appendingPathComponent(release.id, isDirectory: true)
    if FileManager.default.fileExists(atPath: final.path) {
      guard verifiedBundleURL(for: release.id, configuration: configuration) != nil else {
        throw otaError("A stored release with this ID failed local verification")
      }
      return
    }
    try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: false)
    do {
      for (entry, path, hash, size) in files {
        let output = staging.appendingPathComponent(path)
        try FileManager.default.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
        try extractAndVerify(zip, entry: entry, to: output, expectedHash: hash, expectedSize: size)
      }
      try manifestData.write(to: staging.appendingPathComponent("ota-manifest.json"), options: .atomic)
      try canonicalManifest(release.manifest).write(to: staging.appendingPathComponent("release-manifest.json"), options: .atomic)
      try Data(release.signature.utf8).write(to: staging.appendingPathComponent("release-signature.txt"), options: .atomic)
      if FileManager.default.fileExists(atPath: final.path) { throw otaError("Release directory already exists") }
      try FileManager.default.moveItem(at: staging, to: final)
    } catch {
      try? FileManager.default.removeItem(at: staging)
      throw error
    }
  }

  private func verifiedBundleURL(for releaseId: String, configuration: OtaConfiguration) -> URL? {
    let directory = updatesDirectory.appendingPathComponent(releaseId, isDirectory: true)
    do {
      let manifestData = try Data(contentsOf: directory.appendingPathComponent("release-manifest.json"))
      let signature = try String(contentsOf: directory.appendingPathComponent("release-signature.txt"), encoding: .ascii).trimmingCharacters(in: .whitespacesAndNewlines)
      guard let manifest = try JSONSerialization.jsonObject(with: manifestData) as? [String: Any],
            ["app_id", "artifact_sha256", "artifact_size_bytes", "created_at", "manifest_version", "platform", "release_id", "runtime_version", "version"].allSatisfy({ manifest[$0] != nil }),
            manifest["release_id"] as? String == releaseId, manifest["platform"] as? String == "ios",
            manifest["app_id"] as? String == configuration.appId, manifest["runtime_version"] as? String == configuration.runtimeVersion,
            manifest["manifest_version"] as? Int == 1,
            verifySignature(canonicalManifest(manifest), signature: signature, pem: configuration.publicKeyPEM),
            let package = try JSONSerialization.jsonObject(with: Data(contentsOf: directory.appendingPathComponent("ota-manifest.json"))) as? [String: Any],
            package["format_version"] as? Int == 1, package["entrypoint"] as? String == "main.jsbundle",
            package["entrypoint_format"] as? String == "hermes-bytecode", let bundleHash = package["entrypoint_sha256"] as? String,
            let assets = package["assets"] as? [[String: Any]] else { return nil }
      let bundle = directory.appendingPathComponent("main.jsbundle")
      try verifyFile(bundle, expectedHash: bundleHash, expectedSize: (try bundle.resourceValues(forKeys: [.fileSizeKey])).fileSize ?? -1)
      var declared = Set(["main.jsbundle", "ota-manifest.json", "release-manifest.json", "release-signature.txt"])
      for asset in assets {
        guard let path = asset["path"] as? String, path.hasPrefix("assets/"), safePath(path),
              let hash = asset["sha256"] as? String, let size = asset["size_bytes"] as? Int,
              declared.insert(String(path.dropFirst(7))).inserted else { return nil }
        try verifyFile(directory.appendingPathComponent(String(path.dropFirst(7))), expectedHash: hash, expectedSize: size)
      }
      guard let enumerator = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: [.isRegularFileKey]) else { return nil }
      var actual = Set<String>()
      for case let file as URL in enumerator where (try file.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true {
        actual.insert(String(file.path.dropFirst(directory.path.count + 1)))
      }
      return actual == declared ? bundle : nil
    } catch { return nil }
  }

  private func verifyFile(_ url: URL, expectedHash: String, expectedSize: Int) throws {
    let fileSize = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? -1
    guard expectedSize >= 0, fileSize == expectedSize else { throw otaError("OTA file size does not match package manifest") }
    guard try sha256(url) == expectedHash else { throw otaError("OTA file hash does not match package manifest") }
    if url.lastPathComponent == "main.jsbundle" {
      let header = try Data(contentsOf: url, options: .mappedIfSafe).prefix(4)
      guard header.elementsEqual([0xC6, 0x1F, 0xBC, 0x03]) else { throw otaError("iOS entrypoint is not Hermes bytecode") }
    }
  }

  private func extractAndVerify(_ archive: Archive, entry: Entry, to output: URL, expectedHash: String, expectedSize: Int) throws {
    guard expectedSize >= 0, expectedSize <= 500 * 1024 * 1024 else { throw otaError("OTA package entry exceeds limits") }
    guard FileManager.default.createFile(atPath: output.path, contents: nil) else { throw otaError("Cannot create staged OTA file") }
    let handle = try FileHandle(forWritingTo: output)
    defer { try? handle.close() }
    var hasher = SHA256()
    var total = 0
    _ = try archive.extract(entry) { chunk in
      total += chunk.count
      guard total <= expectedSize, total <= 500 * 1024 * 1024 else { throw self.otaError("OTA package entry exceeds declared limits") }
      hasher.update(data: chunk)
      try handle.write(contentsOf: chunk)
    }
    try handle.synchronize()
    let digest = hasher.finalize().map { String(format: "%02x", $0) }.joined()
    guard total == expectedSize, digest == expectedHash else { throw otaError("OTA file hash or size does not match package manifest") }
    if output.lastPathComponent == "main.jsbundle" {
      let header = try Data(contentsOf: output, options: .mappedIfSafe).prefix(4)
      guard header.elementsEqual([0xC6, 0x1F, 0xBC, 0x03]) else { throw otaError("iOS entrypoint is not Hermes bytecode") }
    }
  }

  private func sha256(_ url: URL) throws -> String {
    let handle = try FileHandle(forReadingFrom: url)
    defer { try? handle.close() }
    var hasher = SHA256()
    while let chunk = try handle.read(upToCount: 1024 * 1024), !chunk.isEmpty { hasher.update(data: chunk) }
    return hasher.finalize().map { String(format: "%02x", $0) }.joined()
  }

  private func safePath(_ path: String) -> Bool {
    !path.isEmpty && !path.hasPrefix("/") && !path.contains("\\") && !path.contains(":") && !path.hasSuffix("/") &&
      path.split(separator: "/", omittingEmptySubsequences: false).allSatisfy { !$0.isEmpty && $0 != "." && $0 != ".." }
  }
  private func supportsTransport(_ config: OtaConfiguration) -> Bool {
    config.serverURL.scheme?.lowercased() == "https" || (config.allowLocalHTTP && isLocal(config.serverURL.host))
  }
  private func supportsDownload(_ url: URL, configuration: OtaConfiguration) -> Bool {
    url.scheme?.lowercased() == "https" || (configuration.allowLocalHTTP && isLocal(url.host))
  }
  private func isLocal(_ host: String?) -> Bool { ["localhost", "127.0.0.1", "::1"].contains(host?.lowercased() ?? "") }
  private func sameOrigin(_ lhs: URL, _ rhs: URL) -> Bool {
    lhs.scheme?.lowercased() == rhs.scheme?.lowercased() && lhs.host?.lowercased() == rhs.host?.lowercased() &&
      (lhs.port ?? defaultPort(lhs)) == (rhs.port ?? defaultPort(rhs)) && lhs.user == nil
  }
  private func defaultPort(_ url: URL) -> Int { url.scheme?.lowercased() == "https" ? 443 : 80 }
  private func clearPendingState() {
    [Key.pendingRelease, Key.pendingVersion, Key.previousRelease, Key.previousVersion].forEach { defaults.removeObject(forKey: $0) }
    defaults.set(false, forKey: Key.pendingAttempted)
  }
  private func clearActivationState() {
    [Key.activeRelease, Key.activeVersion, Key.failedRelease].forEach { defaults.removeObject(forKey: $0) }
    clearPendingState()
  }

  private enum CheckStart {
    case proceed(OtaConfiguration)
    case finished([String: Any])
  }

  private func beginCheck() throws -> CheckStart {
    lock.lock()
    defer { lock.unlock() }
    guard let configuration else { throw otaError("OTA SDK has not been initialized") }
    if configuration.isDebugBuild { return .finished(statusDictionary()) }
    guard supportsTransport(configuration) else { throw otaError("Release builds require HTTPS for OTA updates") }
    if defaults.string(forKey: Key.pendingRelease) != nil || checkInProgress {
      return .finished(statusDictionary())
    }
    checkInProgress = true
    return .proceed(configuration)
  }

  private func finishCheck() {
    lock.lock()
    checkInProgress = false
    lock.unlock()
  }

  private func readActivationState(baseVersion: Int) -> OtaActivationState {
    OtaActivationState(
      activeReleaseId: defaults.string(forKey: Key.activeRelease),
      activeVersion: defaults.object(forKey: Key.activeVersion) as? Int ?? baseVersion,
      pendingReleaseId: defaults.string(forKey: Key.pendingRelease),
      pendingVersion: defaults.object(forKey: Key.pendingVersion) as? Int,
      previousReleaseId: defaults.string(forKey: Key.previousRelease),
      previousVersion: defaults.object(forKey: Key.previousVersion) as? Int,
      pendingLaunchAttempted: defaults.bool(forKey: Key.pendingAttempted),
      failedReleaseId: defaults.string(forKey: Key.failedRelease)
    )
  }

  private func persistActivationState(_ state: OtaActivationState) {
    defaults.set(state.activeReleaseId, forKey: Key.activeRelease)
    defaults.set(state.activeVersion, forKey: Key.activeVersion)
    defaults.set(state.pendingReleaseId, forKey: Key.pendingRelease)
    defaults.set(state.pendingVersion, forKey: Key.pendingVersion)
    defaults.set(state.previousReleaseId, forKey: Key.previousRelease)
    defaults.set(state.previousVersion, forKey: Key.previousVersion)
    defaults.set(state.pendingLaunchAttempted, forKey: Key.pendingAttempted)
    defaults.set(state.failedReleaseId, forKey: Key.failedRelease)
  }
  private func otaError(_ message: String) -> NSError {
    NSError(domain: "OtaSdkUpdates", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
  }
}

private final class OtaRedirectBlocker: NSObject, URLSessionTaskDelegate, URLSessionDownloadDelegate {
  func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {}

  func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                  newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
    completionHandler(nil)
  }

  func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                  didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
    if totalBytesWritten > 100 * 1024 * 1024 { downloadTask.cancel() }
  }
}

private extension Archive {
  func extractData(_ entry: Entry, maximumBytes: Int) throws -> Data {
    var result = Data()
    _ = try extract(entry) { chunk in
      guard result.count <= maximumBytes - chunk.count else {
        throw NSError(domain: "OtaSdkUpdates", code: 1, userInfo: [NSLocalizedDescriptionKey: "OTA manifest exceeds size limit"])
      }
      result.append(chunk)
    }
    return result
  }
}

private extension Data {
  func firstRange(of needle: Data) -> Range<Int>? {
    guard !needle.isEmpty, count >= needle.count else { return nil }
    for start in 0...(count - needle.count) where self[start..<(start + needle.count)].elementsEqual(needle) {
      return start..<(start + needle.count)
    }
    return nil
  }
}
