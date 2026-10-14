package io.github.shem2019.proximity.proximity

import io.github.shem2019.proximity.ble.Advertisement
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Turns raw advertisements into a list of readings, nearest first.
 *
 * Every advertisement updates its device's filter at once, but the list is emitted on a
 * fixed tick of [emitIntervalMillis]: a busy room can deliver hundreds of packets a second
 * and a screen needs a few updates a second. Each tick also drops devices gone silent.
 *
 * Changes on [config] reach the running tracker in place, so calibrating a device or
 * switching the smoothing method never restarts the scan.
 *
 * @param clock monotonic milliseconds on the same clock as [Advertisement.timestampMillis].
 */
fun Flow<Advertisement>.toProximityReadings(
    config: Flow<ProximityConfig>,
    clock: () -> Long,
    emitIntervalMillis: Long = 250,
): Flow<List<ProximityReading>> = flow {
    val tracker = ProximityTracker(config.first())
    val ticks = flow {
        while (true) {
            delay(emitIntervalMillis)
            emit(Tick)
        }
    }
    merge(config.map(::Configure), map(::Seen), ticks).collect { event ->
        when (event) {
            is Configure -> tracker.config = event.config
            is Seen -> tracker.update(event.advertisement)
            Tick -> {
                tracker.prune(clock())
                emit(tracker.readings())
            }
        }
    }
}

fun Flow<Advertisement>.toProximityReadings(
    config: ProximityConfig,
    clock: () -> Long,
    emitIntervalMillis: Long = 250,
): Flow<List<ProximityReading>> = toProximityReadings(flowOf(config), clock, emitIntervalMillis)

private sealed interface Event
private data class Configure(val config: ProximityConfig) : Event
private data class Seen(val advertisement: Advertisement) : Event
private data object Tick : Event
