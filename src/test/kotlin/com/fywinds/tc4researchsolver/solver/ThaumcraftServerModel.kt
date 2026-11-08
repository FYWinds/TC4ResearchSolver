package com.fywinds.tc4researchsolver.solver

/**
 * Independent re-implementation of Thaumcraft 4.2.3.5's completion rule
 * (`ResearchManager.checkResearchCompletion` + `checkConnections`), on raw component lists.
 */
object CompletionValidator {
    class Entry(val type: Int, val tag: String?)

    fun isComplete(entries: Map<Hex, Entry>, discovered: Set<String>, components: Map<String, List<String>>): Boolean {
        val roots = entries.filterValues { it.type == 1 }.keys
        if (roots.isEmpty()) return false
        val main = roots.toMutableSet()
        val start = roots.first()
        main.remove(start)
        val checked = HashSet<Hex>()
        fun connections(hex: Hex) {
            checked.add(hex)
            for (d in 0 until 6) {
                val target = NoteGeometry.neighbour(hex, d)
                val targetEntry = entries[target] ?: continue
                if (target in checked || targetEntry.type < 1) continue
                val a1 = entries.getValue(hex).tag
                val a2 = targetEntry.tag
                if (linked(a1, a2, discovered, components)) {
                    if (targetEntry.type == 1) main.remove(target)
                    connections(target)
                }
            }
        }
        connections(start)
        return main.isEmpty()
    }

    private fun linked(a1: String?, a2: String?, discovered: Set<String>, components: Map<String, List<String>>): Boolean {
        if (a1 == null || a2 == null || a1 !in discovered || a2 !in discovered) return false
        val c1 = components[a1].orEmpty()
        val c2 = components[a2].orEmpty()
        return (c1.size == 2 && (c1[0] == a2 || c1[1] == a2)) || (c2.size == 2 && (c2[0] == a1 || c2[1] == a1))
    }
}

/**
 * The server side of the research table as far as our packets are concerned:
 * `PacketAspectCombinationToServer.onMessage` behind TC4Tweaks' `sanityCheckAspectCombination`, and
 * `TileResearchTable.placeAspect`. Any packet the real server would reject, or that only works because the
 * server forgets to charge for it, is recorded in [violations].
 *
 * @param discoveryBonus when true, discovering an aspect by combination yields 3 units like the real server
 *   (`ScanManager.checkAndSyncAspectKnowledge`); when false the worst case of 1 unit is used.
 */
class ThaumcraftServerModel(
    private val components: Map<String, List<String>>,
    grid: Set<Hex>,
    fixed: Map<Hex, String>,
    roots: Set<Hex>,
    pool: Map<String, Int>,
    bonus: Map<String, Int>,
    discovered: Set<String>,
    var ink: Int,
    private val discoveryBonus: Boolean = false,
) {
    val pool = HashMap(pool)
    val bonus = HashMap(bonus.filterValues { it > 0 })
    val discovered = HashSet(discovered)
    val entries: MutableMap<Hex, CompletionValidator.Entry> = LinkedHashMap<Hex, CompletionValidator.Entry>().apply {
        for (h in grid) put(h, CompletionValidator.Entry(if (h in roots) 1 else if (h in fixed) 2 else 0, fixed[h]))
    }
    var complete = false
        private set
    val violations = ArrayList<String>()
    var placementsAfterCompletion = 0
        private set

    private fun poolOf(tag: String) = pool[tag] ?: 0
    private fun bonusOf(tag: String) = bonus[tag] ?: 0

    fun apply(actions: List<Action>) {
        for (action in actions) when (action) {
            is Action.Combine -> combine(action)
            is Action.Place -> place(action)
        }
    }

    private fun combine(a: Action.Combine) {
        val tweaksOk = listOf(a.first, a.second).all { poolOf(it) >= 1 || bonusOf(it) > 0 }
        if (!tweaksOk) {
            violations.add("TC4Tweaks rejects $a (suspicious packet)")
            return
        }
        val vanillaOk = (poolOf(a.first) > 0 || a.firstFromBonus) && (poolOf(a.second) > 0 || a.secondFromBonus)
        if (!vanillaOk) {
            violations.add("vanilla rejects $a")
            return
        }
        val result = components.entries.firstOrNull { (_, c) ->
            c.size == 2 && ((c[0] == a.first && c[1] == a.second) || (c[0] == a.second && c[1] == a.first))
        }?.key
        consume(a.first, a.firstFromBonus, a)
        consume(a.second, a.secondFromBonus, a)
        if (result == null) {
            violations.add("$a has no result")
            return
        }
        if (result != a.result) violations.add("$a produces $result")
        var amount = 1
        if (discovered.add(result) && discoveryBonus) amount += 2
        pool[result] = poolOf(result) + amount
    }

    private fun consume(tag: String, fromBonus: Boolean, a: Action) {
        if (poolOf(tag) <= 0 && fromBonus) {
            if (bonusOf(tag) <= 0) violations.add("$a takes $tag from an empty bonus")
            takeBonus(tag)
        } else if (poolOf(tag) > 0) {
            pool[tag] = poolOf(tag) - 1
        } else {
            violations.add("$a takes $tag for free")
        }
    }

    /** `AspectList.remove(aspect, 1)`: the entry disappears at zero. */
    private fun takeBonus(tag: String) {
        val left = bonusOf(tag) - 1
        if (left <= 0) bonus.remove(tag) else bonus[tag] = left
    }

    private fun place(a: Action.Place) {
        if (ink <= 0) {
            violations.add("$a without ink")
            return
        }
        if (complete) {
            placementsAfterCompletion++
            return
        }
        val entry = entries[a.hex]
        if (entry == null || entry.type != 0) {
            violations.add("$a targets ${if (entry == null) "a hex outside the note" else "an occupied hex"}")
            return
        }
        when {
            poolOf(a.tag) > 0 -> pool[a.tag] = poolOf(a.tag) - 1
            bonusOf(a.tag) > 0 -> takeBonus(a.tag)
            else -> violations.add("$a places ${a.tag} for free")
        }
        entries[a.hex] = CompletionValidator.Entry(2, a.tag)
        ink--
        complete = CompletionValidator.isComplete(entries, discovered, components)
    }
}
