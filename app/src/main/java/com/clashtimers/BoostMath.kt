package com.clashtimers

object BoostMath {
    /**
     * Wall-clock time at which [work] ms of upgrade work, starting at [from], completes.
     * Work runs at 1x outside the (non-overlapping) potion windows and at the potion's
     * multiplier inside them.
     */
    fun finishTime(work: Long, from: Long, boosts: List<Boost>): Long {
        var t = from
        var left = work.toDouble()
        for (b in boosts.sortedBy { it.startAt }) {
            if (b.endsAt <= t) continue
            val start = maxOf(b.startAt, t)
            if (left <= start - t) break
            left -= start - t
            t = start
            val boosted = (b.endsAt - t).toDouble() * b.multiplier
            if (left <= boosted) return t + (left / b.multiplier).toLong()
            left -= boosted
            t = b.endsAt
        }
        return t + left.toLong()
    }
}
