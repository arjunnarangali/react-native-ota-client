package com.otasdk

/** Pure state transitions for selecting a pending update and reverting an unconfirmed boot. */
internal data class OtaActivationState(
    val activeReleaseId: String? = null,
    val activeVersion: Int,
    val pendingReleaseId: String? = null,
    val pendingVersion: Int? = null,
    val previousReleaseId: String? = null,
    val previousVersion: Int? = null,
    val pendingLaunchAttempted: Boolean = false,
    val failedReleaseId: String? = null,
)

internal data class OtaStartupTransition(
    val state: OtaActivationState,
    val selectedReleaseId: String?,
    val failedReleaseId: String? = null,
)

internal object OtaActivationPolicy {
  fun beginStartup(state: OtaActivationState): OtaStartupTransition {
    val pendingId = state.pendingReleaseId ?: return OtaStartupTransition(state, state.activeReleaseId)

    if (!state.pendingLaunchAttempted) {
      return OtaStartupTransition(
          state = state.copy(pendingLaunchAttempted = true),
          selectedReleaseId = pendingId,
      )
    }

    val rolledBack =
        state.copy(
            activeReleaseId = state.previousReleaseId,
            activeVersion = state.previousVersion ?: state.activeVersion,
            pendingReleaseId = null,
            pendingVersion = null,
            previousReleaseId = null,
            previousVersion = null,
            pendingLaunchAttempted = false,
            failedReleaseId = pendingId,
        )
    return OtaStartupTransition(
        state = rolledBack,
        selectedReleaseId = rolledBack.activeReleaseId,
        failedReleaseId = pendingId,
    )
  }

  fun stage(state: OtaActivationState, releaseId: String, version: Int): OtaActivationState =
      state.copy(
          pendingReleaseId = releaseId,
          pendingVersion = version,
          previousReleaseId = state.activeReleaseId,
          previousVersion = state.activeVersion,
          pendingLaunchAttempted = false,
      )

  fun markHealthy(state: OtaActivationState): OtaActivationState {
    val pendingId = state.pendingReleaseId ?: return state
    if (!state.pendingLaunchAttempted) return state

    return state.copy(
        activeReleaseId = pendingId,
        activeVersion = state.pendingVersion ?: state.activeVersion,
        pendingReleaseId = null,
        pendingVersion = null,
        previousReleaseId = null,
        previousVersion = null,
        pendingLaunchAttempted = false,
        failedReleaseId = null,
    )
  }
}
