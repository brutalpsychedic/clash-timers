package com.clashtimers

import org.json.JSONArray
import org.json.JSONObject

/** Widget sections, in display order. */
enum class Group { BUILDERS, LAB, PETS, BUILDER_BASE }

enum class Kind(val group: Group) {
    BUILDING(Group.BUILDERS),
    TRAP(Group.BUILDERS),
    HERO(Group.BUILDERS),
    GUARDIAN(Group.BUILDERS),
    TROOP(Group.LAB),
    SIEGE(Group.LAB),
    SPELL(Group.LAB),
    PET(Group.PETS),
    BB_BUILDING(Group.BUILDER_BASE),
    BB_TRAP(Group.BUILDER_BASE),
    BB_HERO(Group.BUILDER_BASE),
    BB_TROOP(Group.BUILDER_BASE),
}

/** One running upgrade. Times are epoch millis; [startAt] only drives the progress bar. */
data class Upgrade(
    val id: Int,
    val kind: Kind,
    val name: String,
    /** Level the upgrade ends on, or a supercharge tier like "⚡2". */
    val target: String,
    /** Level whose artwork the row shows (buildings and traps have per-level art). */
    val level: Int,
    val goblin: Boolean,
    /** A Builder's Apprentice / Lab Assistant is on it (working now or kept assigned). */
    val helped: Boolean,
    val startAt: Long,
    val finishAt: Long,
) {
    fun sameUpgradeAs(other: Upgrade) =
        id == other.id && kind == other.kind && target == other.target && goblin == other.goblin

    fun progress(now: Long): Float {
        val span = finishAt - startAt
        if (span <= 0) return 1f
        return ((now - startAt).toFloat() / span).coerceIn(0f, 1f)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", kind.name)
        .put("name", name)
        .put("target", target)
        .put("lvl", level)
        .put("goblin", goblin)
        .put("helped", helped)
        .put("start", startAt)
        .put("finish", finishAt)

    companion object {
        fun fromJson(o: JSONObject) = Upgrade(
            id = o.getInt("id"),
            kind = Kind.valueOf(o.getString("kind")),
            name = o.getString("name"),
            target = o.getString("target"),
            level = o.optInt("lvl"),
            goblin = o.optBoolean("goblin"),
            helped = o.optBoolean("helped"),
            startAt = o.getLong("start"),
            finishAt = o.getLong("finish"),
        )
    }
}

/** A potion window: [group] works [multiplier]x faster from [startAt] until [endsAt]. */
data class Boost(val group: Group, val multiplier: Int, val startAt: Long, val endsAt: Long) {
    fun isActive(now: Long) = now in startAt until endsAt

    fun toJson(): JSONObject = JSONObject()
        .put("group", group.name)
        .put("x", multiplier)
        .put("start", startAt)
        .put("end", endsAt)

    companion object {
        fun fromJson(o: JSONObject) = Boost(
            group = Group.valueOf(o.getString("group")),
            multiplier = o.getInt("x"),
            startAt = o.getLong("start"),
            endsAt = o.getLong("end"),
        )
    }
}

/**
 * Builder's Apprentice or Lab Assistant. Each works one 1-hour shift per 23-hour work day
 * (shared by all helpers), adding its level to the upgrade's speed, then sleeps.
 */
data class Helper(
    val id: Int,
    /** BUILDERS for the Apprentice, LAB for the Assistant. */
    val group: Group,
    val level: Int,
    /** Upgrade it's working on, or kept assigned to; null when unknown. */
    val task: String?,
    /** End of the shift running at export time, 0 if none was. */
    val workingUntil: Long,
    /** End of the work day, when it can take a job again; 0 if no day was running. */
    val awakeAt: Long,
    val keepAssigned: Boolean,
) {
    enum class State { WORKING, SLEEPING, READY }

    fun state(now: Long) = when {
        now < workingUntil -> State.WORKING
        now < awakeAt -> State.SLEEPING
        else -> State.READY
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("group", group.name)
        .put("lvl", level)
        .put("task", task)
        .put("workingUntil", workingUntil)
        .put("awakeAt", awakeAt)
        .put("keep", keepAssigned)

    companion object {
        fun fromJson(o: JSONObject) = Helper(
            id = o.getInt("id"),
            group = Group.valueOf(o.getString("group")),
            level = o.optInt("lvl"),
            task = o.optString("task").takeIf { o.has("task") && it.isNotEmpty() },
            workingUntil = o.optLong("workingUntil"),
            awakeAt = o.optLong("awakeAt"),
            keepAssigned = o.optBoolean("keep"),
        )
    }
}

data class Village(
    val tag: String,
    val townHall: Int,
    val homeBuilders: Int,
    val hasLab: Boolean,
    val hasPetHouse: Boolean,
    val hasBuilderBase: Boolean,
    val exportedAt: Long,
    val upgrades: List<Upgrade>,
    val boosts: List<Boost>,
    val helpers: List<Helper> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("tag", tag)
        .put("th", townHall)
        .put("builders", homeBuilders)
        .put("lab", hasLab)
        .put("petHouse", hasPetHouse)
        .put("builderBase", hasBuilderBase)
        .put("exportedAt", exportedAt)
        .put("upgrades", JSONArray(upgrades.map { it.toJson() }))
        .put("boosts", JSONArray(boosts.map { it.toJson() }))
        .put("helpers", JSONArray(helpers.map { it.toJson() }))

    companion object {
        fun fromJson(o: JSONObject) = Village(
            tag = o.optString("tag"),
            townHall = o.optInt("th"),
            homeBuilders = o.optInt("builders", 5),
            hasLab = o.optBoolean("lab"),
            hasPetHouse = o.optBoolean("petHouse"),
            hasBuilderBase = o.optBoolean("builderBase"),
            exportedAt = o.getLong("exportedAt"),
            upgrades = o.optJSONArray("upgrades")?.mapObjects(Upgrade::fromJson) ?: emptyList(),
            boosts = o.optJSONArray("boosts")?.mapObjects(Boost::fromJson) ?: emptyList(),
            helpers = o.optJSONArray("helpers")?.mapObjects(Helper::fromJson) ?: emptyList(),
        )
    }
}

internal inline fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
    for (i in 0 until length()) {
        val o = optJSONObject(i) ?: continue
        action(o)
    }
}

internal fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> {
    val out = ArrayList<T>(length())
    forEachObject { out += transform(it) }
    return out
}
