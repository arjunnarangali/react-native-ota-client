package com.otasdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtaActivationPolicyTest {
  @Test
  fun firstLaunchSelectsPendingBundleAndMarksAttempt() {
    val pending = OtaActivationPolicy.stage(OtaActivationState(activeVersion = 0), "release-1", 1)

    val transition = OtaActivationPolicy.beginStartup(pending)

    assertEquals("release-1", transition.selectedReleaseId)
    assertEquals(true, transition.state.pendingLaunchAttempted)
    assertEquals(0, transition.state.activeVersion)
  }

  @Test
  fun healthyStartupCommitsPendingRelease() {
    val pending =
        OtaActivationPolicy.beginStartup(
            OtaActivationPolicy.stage(OtaActivationState(activeVersion = 0), "release-1", 1)
        )

    val healthy = OtaActivationPolicy.markHealthy(pending.state)

    assertEquals("release-1", healthy.activeReleaseId)
    assertEquals(1, healthy.activeVersion)
    assertNull(healthy.pendingReleaseId)
  }

  @Test
  fun unconfirmedStartupRollsBackToPreviouslyActiveRelease() {
    val previous = OtaActivationState(activeReleaseId = "release-1", activeVersion = 1)
    val pending = OtaActivationPolicy.stage(previous, "release-2", 2)
    val firstStart = OtaActivationPolicy.beginStartup(pending)

    val nextStart = OtaActivationPolicy.beginStartup(firstStart.state)

    assertEquals("release-1", nextStart.selectedReleaseId)
    assertEquals("release-2", nextStart.failedReleaseId)
    assertEquals("release-2", nextStart.state.failedReleaseId)
    assertEquals(1, nextStart.state.activeVersion)
    assertNull(nextStart.state.pendingReleaseId)
  }
}
