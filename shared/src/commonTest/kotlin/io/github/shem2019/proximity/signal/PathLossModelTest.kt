package io.github.shem2019.proximity.signal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PathLossModelTest {

    private val freeSpace = PathLossModel(measuredPowerAt1m = -59.0, pathLossExponent = 2.0)

    @Test
    fun measuredPowerMeansOneMetre() {
        assertEquals(1.0, freeSpace.distanceMeters(-59.0), absoluteTolerance = 1e-9)
    }

    @Test
    fun everyTwentyDbIsTenTimesFurtherInFreeSpace() {
        // d = 10^((P₁ − RSSI) / 10n) = 10^(20 / 20) = 10 and 10^(40 / 20) = 100.
        assertEquals(10.0, freeSpace.distanceMeters(-79.0), absoluteTolerance = 1e-9)
        assertEquals(100.0, freeSpace.distanceMeters(-99.0), absoluteTolerance = 1e-9)
    }

    @Test
    fun aHigherExponentShrinksTheEstimate() {
        // Indoors (n = 3) the same 20 dB loss is only 10^(20 / 30) = 4.64 m.
        val indoors = freeSpace.copy(pathLossExponent = 3.0)
        assertEquals(4.6416, indoors.distanceMeters(-79.0), absoluteTolerance = 1e-4)
    }

    @Test
    fun strongerThanMeasuredPowerIsCloserThanOneMetre() {
        assertEquals(0.5012, freeSpace.distanceMeters(-53.0), absoluteTolerance = 1e-4)
    }

    @Test
    fun rssiAtInvertsDistance() {
        assertEquals(-79.0, freeSpace.rssiAt(10.0), absoluteTolerance = 1e-9)
        listOf(0.3, 1.0, 2.5, 7.0, 40.0).forEach { d ->
            assertEquals(d, freeSpace.distanceMeters(freeSpace.rssiAt(d)), absoluteTolerance = 1e-9)
        }
    }

    @Test
    fun doublingDistanceCostsSixDecibelsInFreeSpace() {
        assertEquals(6.0206, freeSpace.rssiAt(1.0) - freeSpace.rssiAt(2.0), absoluteTolerance = 1e-4)
    }

    @Test
    fun uncertaintyIsMultiplicativeInDistance() {
        // ±4 dB at n = 2 spans 10^(16/20) = 6.31 m to 10^(24/20) = 15.85 m around 10 m.
        val range = freeSpace.distanceRange(-79.0, uncertaintyDb = 4.0)
        assertEquals(6.3096, range.start, absoluteTolerance = 1e-4)
        assertEquals(15.8489, range.endInclusive, absoluteTolerance = 1e-4)
    }

    @Test
    fun freeSpaceLossOverTheFirstMetreIsAboutFortyDb() {
        // 20·log₁₀(4π × 1 m × 2.44 GHz / c) = 20·log₁₀(102.28) = 40.20 dB.
        assertEquals(40.196, PathLossModel.freeSpaceLossDb(), absoluteTolerance = 1e-3)
    }

    @Test
    fun advertisedTxPowerBecomesOneMetrePower() {
        val model = PathLossModel.fromTxPower(txPowerDbm = -20)
        assertEquals(-60.196, model.measuredPowerAt1m, absoluteTolerance = 1e-3)
    }

    @Test
    fun rejectsImpossibleInputs() {
        assertFailsWith<IllegalArgumentException> { PathLossModel(pathLossExponent = 0.0) }
        assertFailsWith<IllegalArgumentException> { freeSpace.rssiAt(0.0) }
    }
}
