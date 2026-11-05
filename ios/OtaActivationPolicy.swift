import Foundation

struct OtaActivationState: Equatable {
  var activeReleaseId: String?
  var activeVersion: Int
  var pendingReleaseId: String?
  var pendingVersion: Int?
  var previousReleaseId: String?
  var previousVersion: Int?
  var pendingLaunchAttempted: Bool
  var failedReleaseId: String?

  init(activeReleaseId: String? = nil, activeVersion: Int, pendingReleaseId: String? = nil,
       pendingVersion: Int? = nil, previousReleaseId: String? = nil, previousVersion: Int? = nil,
       pendingLaunchAttempted: Bool = false, failedReleaseId: String? = nil) {
    self.activeReleaseId = activeReleaseId
    self.activeVersion = activeVersion
    self.pendingReleaseId = pendingReleaseId
    self.pendingVersion = pendingVersion
    self.previousReleaseId = previousReleaseId
    self.previousVersion = previousVersion
    self.pendingLaunchAttempted = pendingLaunchAttempted
    self.failedReleaseId = failedReleaseId
  }
}

struct OtaStartupTransition {
  let state: OtaActivationState
  let selectedReleaseId: String?
  let failedReleaseId: String?
}

enum OtaActivationPolicy {
  static func beginStartup(_ state: OtaActivationState) -> OtaStartupTransition {
    guard let pending = state.pendingReleaseId else {
      return OtaStartupTransition(state: state, selectedReleaseId: state.activeReleaseId, failedReleaseId: nil)
    }
    guard state.pendingLaunchAttempted else {
      var attempted = state
      attempted.pendingLaunchAttempted = true
      return OtaStartupTransition(state: attempted, selectedReleaseId: pending, failedReleaseId: nil)
    }
    var rolledBack = state
    rolledBack.activeReleaseId = state.previousReleaseId
    rolledBack.activeVersion = state.previousVersion ?? state.activeVersion
    rolledBack.pendingReleaseId = nil
    rolledBack.pendingVersion = nil
    rolledBack.previousReleaseId = nil
    rolledBack.previousVersion = nil
    rolledBack.pendingLaunchAttempted = false
    rolledBack.failedReleaseId = pending
    return OtaStartupTransition(state: rolledBack, selectedReleaseId: rolledBack.activeReleaseId, failedReleaseId: pending)
  }

  static func stage(_ state: OtaActivationState, releaseId: String, version: Int) -> OtaActivationState {
    var staged = state
    staged.pendingReleaseId = releaseId
    staged.pendingVersion = version
    staged.previousReleaseId = state.activeReleaseId
    staged.previousVersion = state.activeVersion
    staged.pendingLaunchAttempted = false
    return staged
  }

  static func markHealthy(_ state: OtaActivationState) -> OtaActivationState {
    guard let pending = state.pendingReleaseId, state.pendingLaunchAttempted else { return state }
    var healthy = state
    healthy.activeReleaseId = pending
    healthy.activeVersion = state.pendingVersion ?? state.activeVersion
    healthy.pendingReleaseId = nil
    healthy.pendingVersion = nil
    healthy.previousReleaseId = nil
    healthy.previousVersion = nil
    healthy.pendingLaunchAttempted = false
    healthy.failedReleaseId = nil
    return healthy
  }
}
