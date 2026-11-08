package com.clashtimers

/** Once-a-day freebies shown in the widget's DAILY section. */
enum class DailyKind(val label: Int, val cooldown: Long) {
    /** Resets 24 h after you collect it. Not in the export: tap the row when you do. */
    HOME_STAR(R.string.daily_home_star, 24 * HOUR),
    /** Builder Base 2.0 Star Bonus: 22 h after collecting. Also tap-to-log. */
    BUILDER_STAR(R.string.daily_builder_star, 22 * HOUR),
    /** Free 10x boost, then 22 h; synced from the export or tapped when started. */
    CLOCK_TOWER(R.string.daily_clock_tower, ClockTower.COOLDOWN),
}

data class Daily(
    val kind: DailyKind,
    /** Clock Tower boost end; 0 when not boosting. */
    val activeUntil: Long,
    val activeFrom: Long,
    /** When it can be used again; 0 = never logged, treated as ready. */
    val readyAt: Long,
    /** Art level (Clock Tower). */
    val level: Int = 0,
) {
    enum class State { ACTIVE, COOLDOWN, READY }

    fun state(now: Long) = when {
        now < activeUntil -> State.ACTIVE
        now < readyAt -> State.COOLDOWN
        else -> State.READY
    }

    /** Bar fill: boost used up while active, cooldown elapsed while waiting, full when ready. */
    fun progress(now: Long): Float = when (state(now)) {
        State.ACTIVE -> (now - activeFrom).toFloat() / (activeUntil - activeFrom).coerceAtLeast(1)
        State.COOLDOWN -> 1f - (readyAt - now).toFloat() / kind.cooldown
        State.READY -> 1f
    }.coerceIn(0f, 1f)
}

object Dailies {
    fun from(store: Store): List<Daily> =
        from(store.village, store.homeStarAt, store.builderStarAt, store.showBuilderBase)

    fun from(village: Village?, homeStarAt: Long, builderStarAt: Long, showBuilderBase: Boolean): List<Daily> {
        if (village == null) return emptyList()
        fun star(kind: DailyKind, at: Long) = Daily(kind, 0L, 0L, if (at > 0) at + kind.cooldown else 0L)

        val out = mutableListOf(star(DailyKind.HOME_STAR, homeStarAt))
        if (showBuilderBase && village.hasBuilderBase) {
            out += star(DailyKind.BUILDER_STAR, builderStarAt)
            village.clockTower?.let { tower ->
                val boosting = tower.boostEndsAt > 0
                out += Daily(
                    DailyKind.CLOCK_TOWER,
                    activeUntil = tower.boostEndsAt,
                    activeFrom = if (boosting) tower.boostEndsAt - tower.boostMillis else 0L,
                    readyAt = tower.readyAt,
                    level = tower.level,
                )
            }
        }
        return out
    }
}
