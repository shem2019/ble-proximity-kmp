package io.github.shem2019.proximity.signal

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** Seeded Gaussian noise (Box–Muller), so every test run sees the same "random" RSSI. */
class GaussianNoise(seed: Int, private val sigma: Double) {
    private val random = Random(seed)

    fun next(): Double {
        val u1 = 1.0 - random.nextDouble() // (0, 1], keeps ln() finite
        val u2 = random.nextDouble()
        return sigma * sqrt(-2 * ln(u1)) * cos(2 * PI * u2)
    }
}

fun standardDeviation(values: List<Double>): Double {
    val mean = values.average()
    return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
}
