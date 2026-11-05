import Foundation

func expect(_ condition: @autoclosure () -> Bool, _ message: String) {
  if !condition() {
    fputs("FAILED: \(message)\n", stderr)
    exit(1)
  }
}

let base = OtaActivationState(activeVersion: 0)
let staged = OtaActivationPolicy.stage(base, releaseId: "release-1", version: 1)
let firstStart = OtaActivationPolicy.beginStartup(staged)
expect(firstStart.selectedReleaseId == "release-1", "first launch selects pending release")
expect(firstStart.state.pendingLaunchAttempted, "first launch records attempt")
expect(firstStart.state.activeVersion == 0, "pending is not active before health confirmation")

let healthy = OtaActivationPolicy.markHealthy(firstStart.state)
expect(healthy.activeReleaseId == "release-1", "health confirmation commits release")
expect(healthy.activeVersion == 1 && healthy.pendingReleaseId == nil, "health confirmation clears pending state")

let current = OtaActivationState(activeReleaseId: "release-1", activeVersion: 1)
let next = OtaActivationPolicy.stage(current, releaseId: "release-2", version: 2)
let failedLaunch = OtaActivationPolicy.beginStartup(next)
let rollback = OtaActivationPolicy.beginStartup(failedLaunch.state)
expect(rollback.selectedReleaseId == "release-1", "unconfirmed launch restores previous release")
expect(rollback.failedReleaseId == "release-2", "rollback records failed release")
expect(rollback.state.activeVersion == 1 && rollback.state.pendingReleaseId == nil, "rollback restores previous version")

print("iOS OTA activation policy checks passed")
