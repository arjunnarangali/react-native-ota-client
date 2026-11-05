import UIKit
import OtaSdk

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
  func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    let info = Bundle.main.infoDictionary ?? [:]
    let serverURL = info["OTA_SERVER_URL"] as? String ?? ""
    let runtimeVersion = info["OTA_RUNTIME_VERSION"] as? String ?? ""
    let channel = info["OTA_CHANNEL"] as? String ?? ""
    let appId = info["OTA_APP_ID"] as? String ?? Bundle.main.bundleIdentifier ?? ""
    let baseVersion = info["OTA_BASE_VERSION"] as? Int ?? 0
    let allowLocalHTTP = info["OTA_ALLOW_LOCAL_HTTP"] as? Bool ?? false
    let publicKey = Bundle.main.url(forResource: "ota_publisher_public", withExtension: "pem")
      .flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? ""

    if !serverURL.isEmpty, !runtimeVersion.isEmpty, !channel.isEmpty, !publicKey.isEmpty {
      OtaSdkUpdates.initialize(
        serverUrl: serverURL,
        runtimeVersion: runtimeVersion,
        channel: channel,
        baseVersion: NSNumber(value: baseVersion),
        publicKeyPem: publicKey,
        isDebugBuild: _isDebugAssertConfiguration(),
        appId: appId,
        allowLocalHttp: allowLocalHTTP
      )
      OtaSdkUpdates.beginProcess()
    }
    return true
  }
}
