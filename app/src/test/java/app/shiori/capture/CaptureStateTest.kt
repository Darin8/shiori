package app.shiori.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureStateTest {
    @Test
    fun `consent makes Ready`() {
        assertEquals(CaptureState.Ready, CaptureState.NoConsent.next(CaptureEvent.ConsentGranted))
    }

    @Test
    fun `ProjectionStopped from any state returns NoConsent`() {
        for (s in listOf(CaptureState.NoConsent, CaptureState.Ready, CaptureState.Capturing)) {
            assertEquals(CaptureState.NoConsent, s.next(CaptureEvent.ProjectionStopped))
        }
    }

    @Test
    fun `CaptureRequested while Capturing stays Capturing`() {
        assertEquals(CaptureState.Capturing, CaptureState.Capturing.next(CaptureEvent.CaptureRequested))
    }

    @Test
    fun `CaptureRequested from Ready starts Capturing and NoConsent is unchanged`() {
        assertEquals(CaptureState.Capturing, CaptureState.Ready.next(CaptureEvent.CaptureRequested))
        assertEquals(CaptureState.NoConsent, CaptureState.NoConsent.next(CaptureEvent.CaptureRequested))
    }

    @Test
    fun `FrameDelivered returns Ready`() {
        assertEquals(CaptureState.Ready, CaptureState.Capturing.next(CaptureEvent.FrameDelivered))
    }

    @Test
    fun `FrameDelivered after projection stopped stays NoConsent`() {
        assertEquals(CaptureState.NoConsent, CaptureState.NoConsent.next(CaptureEvent.FrameDelivered))
    }
}
