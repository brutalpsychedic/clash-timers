package com.clashtimers

import org.json.JSONException
import org.json.JSONObject

/**
 * Reads the JSON from Clash of Clans' Settings > More Settings > Data Export.
 *
 * Rows look like {"data": 1000019, "lvl": 14, "timer": 3600, "extra": true}: `timer` is the
 * seconds left at the export's `timestamp`, `extra` marks the Goblin Builder / Researcher.
 */
object ExportParser {
    private val SECTIONS = listOf(
        "buildings" to Kind.BUILDING,
        "traps" to Kind.TRAP,
        "heroes" to Kind.HERO,
        "guardians" to Kind.GUARDIAN,
        "units" to Kind.TROOP,
        "siege_machines" to Kind.SIEGE,
        "spells" to Kind.SPELL,
        "pets" to Kind.PET,
        "buildings2" to Kind.BB_BUILDING,
        "traps2" to Kind.BB_TRAP,
        "heroes2" to Kind.BB_HERO,
        "units2" to Kind.BB_TROOP,
    )

    private const val TOWN_HALL = 1000001
    private const val LABORATORY = 1000007
    private const val BUILDERS_HUT = 1000015
    private const val BOB_HUT = 1000064
    private const val BOB_CONTROL = 1000065 // Builder Base; level 5 sends B.O.B home as 6th builder
    private const val PET_HOUSE = 1000068

    // A Town Hall timer with a weapon below this level is read as a weapon upgrade.
    private const val MAX_WEAPON_LEVEL = 5

    // helpers[] rows: {"data": 93000000, "lvl": 8, "helper_cooldown": 49617}. The helped
    // upgrade row carries "helper_timer" (seconds left in the shift) and "helper_recurrent"
    // ("keep assigned").
    private val HELPER_GROUPS = mapOf(93000000 to Group.BUILDERS, 93000001 to Group.LAB) // Apprentice, Lab Assistant
    private const val HELPER_SHIFT = HOUR
    private const val HELPER_WORK_DAY = 23 * HOUR

    private class Assignment(val task: String, val shiftSeconds: Long, val keepAssigned: Boolean)

    fun looksLikeExport(text: String?): Boolean {
        val t = text?.trim() ?: return false
        return t.startsWith("{") && t.contains("\"buildings\"")
    }

    /** @param previous last parsed village, used to keep progress bars steady across syncs. */
    fun parse(text: String, game: GameData, boosts: List<Boost>, previous: Village?): Village {
        val root = try {
            JSONObject(text.trim())
        } catch (e: JSONException) {
            throw IllegalArgumentException("That isn't a Clash of Clans village export.")
        }
        val buildings = root.optJSONArray("buildings")
            ?: throw IllegalArgumentException("The export has no buildings list.")
        val exportedAt = root.optLong("timestamp").takeIf { it > 0 }?.let { it * 1000 }
            ?: System.currentTimeMillis()

        var townHall = 0
        var huts = 0
        var bob = false
        var lab = false
        var petHouse = false
        buildings.forEachObject {
            when (it.optInt("data")) {
                TOWN_HALL -> townHall = maxOf(townHall, it.optInt("lvl"))
                BUILDERS_HUT -> huts += it.optInt("cnt", 1)
                BOB_HUT -> bob = true
                LABORATORY -> lab = true
                PET_HOUSE -> petHouse = true
            }
        }
        val builderBase = root.optJSONArray("buildings2")
        builderBase?.forEachObject {
            if (it.optInt("data") == BOB_CONTROL && it.optInt("lvl") >= 5) bob = true
        }

        val helperRows = HashMap<Int, JSONObject>()
        root.optJSONArray("helpers")?.forEachObject {
            if (it.optInt("data") in HELPER_GROUPS) helperRows[it.optInt("data")] = it
        }
        val helperLevels = HELPER_GROUPS.entries.associate { (id, group) -> group to (helperRows[id]?.optInt("lvl") ?: 0) }

        val liveBoosts = boosts.filter { it.endsAt > exportedAt }
        val upgrades = ArrayList<Upgrade>()
        val assignments = HashMap<Group, Assignment>()
        for ((key, kind) in SECTIONS) {
            root.optJSONArray(key)?.forEachObject { row ->
                val timer = row.optLong("timer")
                if (timer <= 0) return@forEachObject
                val upgrade = upgrade(row, kind, timer, exportedAt, game, liveBoosts, helperLevels[kind.group], previous)
                upgrades += upgrade
                if (upgrade.helped) {
                    assignments[kind.group] =
                        Assignment(upgrade.name, row.optLong("helper_timer"), row.isTruthy("helper_recurrent"))
                }
            }
        }

        val helpers = HELPER_GROUPS.mapNotNull { (id, group) ->
            val row = helperRows[id]
            val job = assignments[group]
            if (row == null && job == null) return@mapNotNull null
            val workingUntil = if (job != null && job.shiftSeconds > 0) exportedAt + job.shiftSeconds * 1000 else 0L
            val cooldown = row?.optLong("helper_cooldown") ?: 0L
            val awakeAt = when {
                cooldown > 0 -> exportedAt + cooldown * 1000
                // No countdown exported: the shift that's running started the work day.
                workingUntil > 0 -> workingUntil - HELPER_SHIFT + HELPER_WORK_DAY
                else -> 0L
            }
            Helper(id, group, row?.optInt("lvl") ?: 0, job?.task, workingUntil, awakeAt, job?.keepAssigned == true)
        }

        return Village(
            tag = root.optString("tag"),
            townHall = townHall,
            homeBuilders = (if (huts > 0) huts else 5) + if (bob) 1 else 0,
            hasLab = lab,
            hasPetHouse = petHouse,
            hasBuilderBase = builderBase != null && builderBase.length() > 0,
            exportedAt = exportedAt,
            upgrades = upgrades,
            boosts = liveBoosts,
            helpers = helpers,
        )
    }

    private fun upgrade(
        row: JSONObject,
        kind: Kind,
        timer: Long,
        exportedAt: Long,
        game: GameData,
        boosts: List<Boost>,
        helperLevel: Int?, // null: this section has no helper
        previous: Village?,
    ): Upgrade {
        val id = row.optInt("data")
        val level = row.optInt("lvl")
        var name = game.name(id) ?: if (kind == Kind.GUARDIAN) "Guardian" else "Item #$id"
        val target: String
        var artLevel = level
        var stepSeconds = 0L
        when {
            // The supercharge field counts finished tiers, so a running one is the next tier.
            row.has("supercharge") -> target = "⚡${row.optInt("supercharge") + 1}"
            id == TOWN_HALL && row.has("weapon") && row.optInt("weapon") < MAX_WEAPON_LEVEL -> {
                name = "Town Hall Weapon"
                target = "${row.optInt("weapon") + 1}"
            }
            else -> {
                target = "${level + 1}"
                artLevel = level + 1 // show what it's becoming
                stepSeconds = game.stepSeconds(id, level)
            }
        }

        val shiftSeconds = if (helperLevel != null) row.optLong("helper_timer") else 0L
        val helped = helperLevel != null && (shiftSeconds > 0 || row.isTruthy("helper_recurrent"))
        val finishAt = BoostMath.finishTime(
            timer * 1000,
            exportedAt,
            boosts.filter { it.group == kind.group },
            helperBonus = helperLevel ?: 0,
            helperUntil = exportedAt + shiftSeconds * 1000,
        )
        val draft = Upgrade(id, kind, name, target, artLevel, row.isTruthy("extra"), helped, exportedAt, finishAt)
        // Known duration: back-date the start. Unknown: keep the start we saw last sync.
        val startAt = if (stepSeconds >= timer) {
            exportedAt - (stepSeconds - timer) * 1000
        } else {
            previous?.upgrades?.firstOrNull { it.sameUpgradeAs(draft) }?.startAt ?: exportedAt
        }
        return draft.copy(startAt = startAt)
    }

    private fun JSONObject.isTruthy(key: String): Boolean = when (val v = opt(key)) {
        is Boolean -> v
        is Number -> v.toInt() != 0
        is String -> v == "true" || v == "1"
        else -> false
    }
}
