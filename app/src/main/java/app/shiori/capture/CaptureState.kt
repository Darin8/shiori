package app.shiori.capture

enum class CaptureEvent { ConsentGranted, ProjectionStopped, CaptureRequested, FrameDelivered }

sealed interface CaptureState {
    object NoConsent : CaptureState
    object Ready : CaptureState
    object Capturing : CaptureState

    fun next(event: CaptureEvent): CaptureState = when (event) {
        CaptureEvent.ConsentGranted -> if (this === NoConsent) Ready else this
        CaptureEvent.ProjectionStopped -> NoConsent
        CaptureEvent.CaptureRequested -> if (this === Ready) Capturing else this
        CaptureEvent.FrameDelivered -> if (this === Capturing) Ready else this
    }
}
