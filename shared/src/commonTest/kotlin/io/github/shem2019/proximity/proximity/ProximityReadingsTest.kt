package io.github.shem2019.proximity.proximity

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class) // TestCoroutineScheduler.currentTime
class ProximityReadingsTest {

    @Test
    fun emitsOnEachTickAndDropsSilentDevices() = runTest {
        val advertisements = flow {
            emit(ad("a", rssi = -59, at = 0))
            delay(400)
            emit(ad("b", rssi = -79, at = 400))
            awaitCancellation()
        }
        val config = ProximityConfig(staleAfterMillis = 2_000)

        val ticks = advertisements
            .toProximityReadings(config, clock = { testScheduler.currentTime }, emitIntervalMillis = 250)
            .take(10)
            .toList()
            .map { readings -> readings.map { it.deviceId } }

        // Ticks at 250, 500, … 2500 ms. "a" (last heard at 0) goes after 2250;
        // "b" (last heard at 400) goes after 2500.
        assertEquals(listOf("a"), ticks[0])
        assertEquals(List(7) { listOf("a", "b") }, ticks.subList(1, 8))
        assertEquals(listOf("b"), ticks[8])
        assertEquals(emptyList(), ticks[9])
    }

    @Test
    fun aConfigChangeReachesTheRunningPipeline() = runTest {
        var scanStarts = 0
        val advertisements = flow {
            scanStarts++
            while (true) {
                emit(ad("a", rssi = -69, at = testScheduler.currentTime))
                delay(100)
            }
        }
        val config = MutableStateFlow(ProximityConfig())

        val distances = mutableListOf<Double>()
        advertisements
            .toProximityReadings(config, clock = { testScheduler.currentTime }, emitIntervalMillis = 250)
            .take(2)
            .collect { readings ->
                distances += readings.single().distanceMeters
                config.value = ProximityConfig(calibrations = mapOf("a" to -69.0))
            }

        // Default P₁ = -59: 10 dB below is 3.16 m. Calibrated to -69: exactly 1 m.
        assertEquals(3.1623, distances[0], absoluteTolerance = 1e-4)
        assertEquals(1.0, distances[1], absoluteTolerance = 1e-9)
        assertEquals(1, scanStarts)
    }

    @Test
    fun cancellingTheReadingsStopsTheScan() = runTest {
        var scanStopped = false
        val advertisements = flow {
            emit(ad("a", rssi = -60))
            awaitCancellation()
        }.onCompletion { scanStopped = true }

        advertisements.toProximityReadings(ProximityConfig(), clock = { testScheduler.currentTime }).first()

        assertTrue(scanStopped)
    }
}
