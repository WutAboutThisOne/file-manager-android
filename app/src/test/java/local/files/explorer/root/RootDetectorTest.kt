package local.files.explorer.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootDetectorTest {
    private class FakeProbe(
        val granted: Boolean? = null,
        val su: Boolean = false,
        val request: () -> Boolean = { error("must not prompt") },
        val grantedThrows: Boolean = false,
        val suThrows: Boolean = false,
    ) : RootProbe {
        var prompted = false
        override fun alreadyGranted(): Boolean? = if (grantedThrows) throw IllegalStateException() else granted
        override fun suBinaryPresent(): Boolean = if (suThrows) throw SecurityException() else su
        override fun requestRoot(): Boolean { prompted = true; return request() }
    }

    @Test fun unrootedPhoneNeverPrompts() {
        val p = FakeProbe(granted = false, su = false)
        assertEquals(RootState.NOT_AVAILABLE, RootDetector(p).detect())
        assertFalse(p.prompted)
    }

    @Test fun alreadyGrantedSkipsPrompt() {
        val p = FakeProbe(granted = true)
        assertEquals(RootState.GRANTED, RootDetector(p).detect())
        assertFalse(p.prompted)
    }

    @Test fun suPresentAndAllowed() =
        assertEquals(RootState.GRANTED, RootDetector(FakeProbe(su = true, request = { true })).detect())

    @Test fun suPresentButDenied() =
        assertEquals(RootState.DENIED, RootDetector(FakeProbe(su = true, request = { false })).detect())

    @Test fun shellCrashOrTimeoutCountsAsDenied() =
        assertEquals(RootState.DENIED, RootDetector(FakeProbe(su = true, request = { throw RuntimeException("timeout") })).detect())

    @Test fun probeExceptionsFallBackToNotAvailable() {
        val p = FakeProbe(grantedThrows = true, suThrows = true)
        assertEquals(RootState.NOT_AVAILABLE, RootDetector(p).detect())
        assertFalse(p.prompted)
    }

    @Test fun forcedCheckWithoutSuStillHandlesFailure() {
        assertEquals(RootState.NOT_AVAILABLE, RootDetector(FakeProbe(request = { throw RuntimeException() })).detect(force = true))
        assertEquals(RootState.NOT_AVAILABLE, RootDetector(FakeProbe(request = { false })).detect(force = true))
        assertEquals(RootState.GRANTED, RootDetector(FakeProbe(request = { true })).detect(force = true))
    }

    @Test fun findSuChecksKnownPathsAndPath() {
        assertFalse(RootDetector.findSu(path = "/usr/bin:/bin", exists = { false }))
        assertTrue(RootDetector.findSu(path = "", exists = { it == "/system/xbin/su" }))
        assertTrue(RootDetector.findSu(path = "/opt/magisk/", exists = { it == "/opt/magisk/su" }))
        assertFalse(RootDetector.findSu(path = null, exists = { throw SecurityException() }))
    }
}
