package com.fywinds.tc4researchsolver.solver

/** One packet to the server. */
sealed interface Action {
    /**
     * `PacketAspectCombinationToServer(first, second, firstFromBonus, secondFromBonus)`; the flags are what
     * the vanilla GUI sends: whether the table bonus holds the aspect.
     */
    data class Combine(
        val first: String,
        val second: String,
        val firstFromBonus: Boolean,
        val secondFromBonus: Boolean,
        val result: String,
    ) : Action

    /** `PacketAspectPlaceToServer(q, r, aspect)` on a vacant hex. */
    data class Place(val hex: Hex, val tag: String) : Action
}

/** Server-side aspect state as last synced to the client: pool and table bonus are kept separate. */
data class AspectState(val pool: Map<String, Int>, val bonus: Map<String, Int>, val discovered: Set<String>)

/**
 * Conservative simulation of the server, used to order and check packets before anything is sent.
 *
 * Mirrors Thaumcraft 4.2.3.5 + Thaumcraft4Tweaks:
 * - Combination (`PacketAspectCombinationToServer.onMessage`, after TC4Tweaks'
 *   `ASMCallhookServer.sanityCheckAspectCombination`): both inputs need pool >= 1 or bonus > 0 at the time
 *   the packet is processed; each input is then taken from the pool, or from the bonus when the pool is
 *   empty and the bonus flag is set. The result is added to the pool (at least 1 unit) and discovered.
 * - Placement (`TileResearchTable.placeAspect`): one ink; the aspect is taken from the pool, or from the
 *   bonus when the pool is empty.
 *
 * Every deviation of the real server from this simulation only adds aspects (discovery bonus of +2,
 * RESEARCHER2's free placement, bonus regeneration), so a packet covered here is covered there.
 */
class SimulatedServer(initial: AspectState, private var ink: Int) {
    private val pool = HashMap(initial.pool)
    private val bonus = HashMap(initial.bonus)
    private val discovered = HashSet(initial.discovered)

    fun units(tag: String): Int = (pool[tag] ?: 0).coerceAtLeast(0) + (bonus[tag] ?: 0).coerceAtLeast(0)

    fun isDiscovered(tag: String): Boolean = tag in discovered

    fun inkLeft(): Int = ink

    private fun covered(tag: String): Boolean = (pool[tag] ?: 0) >= 1 || (bonus[tag] ?: 0) > 0

    /** Returns the packet to send, or null when the simulation cannot cover it. */
    fun combine(first: String, second: String, result: String): Action.Combine? {
        if (!covered(first) || !covered(second)) return null
        if (first == second && units(first) < 2) return null
        val action = Action.Combine(first, second, (bonus[first] ?: 0) > 0, (bonus[second] ?: 0) > 0, result)
        take(first)
        take(second)
        pool[result] = (pool[result] ?: 0) + 1
        discovered.add(result)
        return action
    }

    fun place(hex: Hex, tag: String): Action.Place? {
        if (ink < 1 || !covered(tag)) return null
        take(tag)
        ink--
        return Action.Place(hex, tag)
    }

    private fun take(tag: String) {
        val p = pool[tag] ?: 0
        if (p > 0) pool[tag] = p - 1 else bonus[tag] = (bonus[tag] ?: 0) - 1
    }
}

/** Orders a plan into packets: combinations in dependency order, then placements. */
object PacketScheduler {
    sealed interface Result {
        data class Scheduled(val actions: List<Action>) : Result

        /** The plan is not covered by the simulated state; [blocked] is the first packet that is not. */
        data class Uncovered(val blocked: String) : Result
    }

    fun schedule(
        graph: AspectGraph,
        crafted: Map<String, Int>,
        placements: Map<Hex, String>,
        state: AspectState,
        ink: Int,
    ): Result {
        val server = SimulatedServer(state, ink)
        val actions = ArrayList<Action>()
        val crafts = crafted.entries
            .filter { it.value > 0 }
            .sortedBy { graph.depth(graph.indexOf(it.key)) }
        for ((tag, count) in crafts) {
            val components = graph.componentsOf(tag)
            if (components.size != 2) return Result.Uncovered("combine -> $tag")
            repeat(count) {
                val action = server.combine(components[0], components[1], tag)
                    ?: return Result.Uncovered("combine ${components[0]} + ${components[1]} -> $tag")
                actions.add(action)
            }
        }
        for ((hex, tag) in placements) {
            val action = server.place(hex, tag) ?: return Result.Uncovered("place $tag at $hex")
            actions.add(action)
        }
        return Result.Scheduled(actions)
    }
}
