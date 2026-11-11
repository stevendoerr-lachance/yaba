package dev.steven.s3control.ui.onboarding

import kotlin.math.floor

/**
 * The arithmetic behind "no onboarding screen ever scrolls": which rung of a step's ladder fits the height that is
 * left. Pure and in dp, so it is tested on the JVM; [OnboardingFrame] feeds it the measured needs.
 *
 * A ladder is a list of rungs, richest first. Each rung takes one more thing away (the caption, the rail, the hero ...),
 * so the needs never grow from one rung to the next.
 */
object LadderMath {
    /** The available height is rounded down to this step, so a keyboard that grows by a pixel does not flip a rung. */
    const val QUANT_DP = 8

    /** Going to a richer rung needs this much more room than the rung itself, so the layout does not flicker at the edge. */
    const val HYST_DP = 16

    fun quantize(heightDp: Float): Float = floor(heightDp / QUANT_DP) * QUANT_DP

    /**
     * The first rung whose [needs] fit into [available] (both in dp, [needs] richest first and non-increasing). Rungs richer
     * than [current] must fit with [HYST_DP] to spare; [current] and poorer ones only have to fit. When nothing fits, the
     * last (poorest) rung is the answer: it is the one a step designs for the smallest window.
     */
    fun choose(needs: FloatArray, available: Float, current: Int): Int {
        if (needs.isEmpty()) return 0
        val q = quantize(available)
        for (k in needs.indices) {
            val margin = if (k < current) HYST_DP else 0
            if (needs[k] + margin <= q) return k
        }
        return needs.lastIndex
    }
}
