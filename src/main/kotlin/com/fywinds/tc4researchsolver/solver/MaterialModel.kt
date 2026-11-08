package com.fywinds.tc4researchsolver.solver

/**
 * Exact aspect accounting for a whole plan.
 *
 * Cost model (user-confirmed): a placed unit taken directly from stock (player pool + table bonus, minus
 * what this plan already consumes) costs `weight × discount`. A unit that must be crafted costs the full
 * weights of the inputs it consumes, recursively: an input from stock costs `weight` (no discount), an
 * input that is crafted itself costs its own crafting cost. Uncraftable aspects (primals) that run short
 * make the plan infeasible.
 *
 * Depletion is exact: demand is summed per aspect over the whole plan before stock is applied, processing
 * compounds before their components (crafting a compound adds one unit of demand to each component).
 *
 * Discovery: every aspect in the root network must be discovered when the server checks completion
 * (`ResearchManager.checkConnections` calls `hasDiscoveredAspect` for both sides of a link). An
 * undiscovered network aspect therefore needs at least one combination producing it
 * (`ScanManager.checkAndSyncAspectKnowledge` discovers the result of a combination).
 */
class MaterialModel(
    val graph: AspectGraph,
    stock: Map<String, Int>,
    discovered: Set<String>,
    weights: AspectWeights,
    private val discount: Double,
) {
    private val n = graph.size
    private val stockUnits = IntArray(n) { maxOf(0, stock[graph.tags[it]] ?: 0) }
    private val known = BooleanArray(n) { graph.tags[it] in discovered }
    private val weight = DoubleArray(n) { weights.of(graph.tags[it]) }

    /** Compounds before their components. */
    private val order: IntArray = (0 until n).sortedByDescending { graph.depth(it) }.toIntArray()

    fun isDiscovered(index: Int): Boolean = known[index]

    fun stockOf(index: Int): Int = stockUnits[index]

    /** Penalised cost used by the search: stock cost plus [SHORT_PENALTY] per missing unit. */
    fun score(direct: IntArray, network: BooleanArray, need: IntArray): Double {
        direct.copyInto(need)
        var cost = 0.0
        var short = 0
        for (t in order) {
            val demand = need[t]
            val mustCraft = network[t] && !known[t]
            if (!graph.isCraftable(t)) {
                if (mustCraft) short += 1
                val used = minOf(demand, stockUnits[t])
                short += demand - used
                cost += stockCost(t, used, demand, direct[t])
            } else {
                val crafted = maxOf(if (mustCraft) 1 else 0, demand - stockUnits[t])
                cost += stockCost(t, maxOf(0, demand - crafted), demand, direct[t])
                if (crafted > 0) for (c in graph.componentsOf(t)) need[c] += crafted
            }
        }
        return cost + short * SHORT_PENALTY
    }

    /** Full breakdown of [direct] placements with the aspects in [network] required to be discovered. */
    fun evaluate(direct: IntArray, network: BooleanArray): MaterialPlan {
        val need = direct.copyOf()
        val used = LinkedHashMap<String, Int>()
        val crafted = LinkedHashMap<String, Int>()
        val shortfall = LinkedHashMap<String, Int>()
        var cost = 0.0
        for (t in order) {
            val tag = graph.tags[t]
            val demand = need[t]
            val mustCraft = network[t] && !known[t]
            if (!graph.isCraftable(t)) {
                val take = minOf(demand, stockUnits[t])
                val missing = demand - take + if (mustCraft) 1 else 0
                if (take > 0) used[tag] = take
                if (missing > 0) shortfall[tag] = missing
                cost += stockCost(t, take, demand, direct[t])
            } else {
                val make = maxOf(if (mustCraft) 1 else 0, demand - stockUnits[t])
                val take = maxOf(0, demand - make)
                if (take > 0) used[tag] = take
                if (make > 0) {
                    crafted[tag] = make
                    for (c in graph.componentsOf(t)) need[c] += make
                }
                cost += stockCost(t, take, demand, direct[t])
            }
        }
        return MaterialPlan(cost, used, crafted, shortfall)
    }

    /**
     * Cost of [take] stock units of aspect [t] when [placed] of its [demand] units are placements: those get
     * the discount, units consumed by combinations cost the full weight. Stock goes where it is cheapest.
     */
    private fun stockCost(t: Int, take: Int, demand: Int, placed: Int): Double {
        val discounted = if (discount <= 1.0) minOf(take, placed) else maxOf(0, take - (demand - placed))
        return weight[t] * (discounted * discount + (take - discounted))
    }

    companion object {
        const val SHORT_PENALTY: Double = 1.0e7
    }
}

/** Result of [MaterialModel.evaluate]: stock units consumed, combinations needed, and missing units. */
class MaterialPlan(
    val cost: Double,
    val used: Map<String, Int>,
    val crafted: Map<String, Int>,
    val shortfall: Map<String, Int>,
) {
    val feasible: Boolean get() = shortfall.isEmpty()
}
