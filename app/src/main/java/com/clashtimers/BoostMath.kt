package com.clashtimers

object BoostMath {
    /**
     * Wall-clock time at which [work] ms of upgrade work, starting at [from], completes.
     * Work runs at 1x, or at a potion's multiplier inside its (non-overlapping) window.
     * A helper on shift adds [helperBonus] (its level) on top of that until [helperUntil]:
     * a level 8 Apprentice during a Builder Potion runs at 10 + 8 = 18x.
     */
    fun finishTime(work: Long, from: Long, potions: List<Boost>, helperBonus: Int = 0, helperUntil: Long = from): Long {
        val edges = (potions.flatMap { listOf(it.startAt, it.endsAt) } + helperUntil).filter { it > from }.sorted()
        var t = from
        var left = work.toDouble()
        for (edge in edges) {
            val rate = rateAt(t, potions, helperBonus, helperUntil)
            val capacity = (edge - t) * rate
            if (left <= capacity) return t + (left / rate).toLong()
            left -= capacity
            t = edge
        }
        return t + (left / rateAt(t, potions, helperBonus, helperUntil)).toLong()
    }

    private fun rateAt(t: Long, potions: List<Boost>, helperBonus: Int, helperUntil: Long): Double {
        val potion = potions.firstOrNull { it.isActive(t) }?.multiplier ?: 1
        return (potion + if (t < helperUntil) helperBonus else 0).toDouble()
    }
}
