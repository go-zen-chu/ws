package xyz.amasuda.ws

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomGesturesTest {
    private fun pinch(vararg factors: Float): List<Int> = PinchSteps().run {
        start()
        factors.map { scaleBy(it) }
    }

    @Test
    fun ifTheFingersSpreadALittleItShouldNotStepYet() {
        assertEquals(listOf(0, 0), pinch(1.05f, 1.05f))
    }

    @Test
    fun ifTheFingersSpreadFarEnoughItShouldStepLargerOnce() {
        assertEquals(listOf(0, 1, 0), pinch(1.15f, 1.15f, 1.05f))
    }

    @Test
    fun ifTheFingersCloseFarEnoughItShouldStepSmaller() {
        assertEquals(listOf(0, -1), pinch(0.9f, 0.85f))
    }

    @Test
    fun ifThePinchKeepsSpreadingItShouldStepAgainFromTheLastStep() {
        assertEquals(listOf(1, 1), pinch(1.3f, 1.3f))
    }

    @Test
    fun ifANewPinchStartsItShouldForgetThePreviousOne() {
        val steps = PinchSteps()
        steps.start()
        steps.scaleBy(1.2f)
        steps.start()
        assertEquals(0, steps.scaleBy(1.1f))
    }

    @Test
    fun ifTheWheelTurnsUpOrDownItShouldStepLargerOrSmaller() {
        assertEquals(1, WheelZoom.step(1f))
        assertEquals(-1, WheelZoom.step(-0.5f))
        assertEquals(0, WheelZoom.step(0f))
    }
}
