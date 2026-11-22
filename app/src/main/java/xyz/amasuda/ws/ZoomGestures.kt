package xyz.amasuda.ws

/**
 * Turns a pinch into text size steps: each time the fingers spread or close by [STEP_RATIO]
 * since the last step, the text gets one step larger or smaller.
 */
class PinchSteps {
    private var scale = 1f

    /** A new pinch began. */
    fun start() {
        scale = 1f
    }

    /** Applies one scale factor of the pinch: 1 for a step larger, -1 for a step smaller, 0 for none yet. */
    fun scaleBy(factor: Float): Int {
        scale *= factor
        return when {
            scale >= STEP_RATIO -> 1.also { scale = 1f }
            scale <= 1f / STEP_RATIO -> (-1).also { scale = 1f }
            else -> 0
        }
    }

    private companion object {
        const val STEP_RATIO = 1.25f
    }
}

/** Ctrl + mouse wheel (or a DeX touchpad pinch reported as one): 1 to make the text larger, -1 smaller, 0 for none. */
object WheelZoom {
    fun step(vertical: Float): Int = when {
        vertical > 0f -> 1
        vertical < 0f -> -1
        else -> 0
    }
}
