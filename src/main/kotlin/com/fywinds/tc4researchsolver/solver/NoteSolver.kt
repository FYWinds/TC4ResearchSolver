package com.fywinds.tc4researchsolver.solver

import java.util.ArrayDeque
import java.util.Random

/**
 * A research note as the solver sees it.
 *
 * @property grid every hex of the note (`ResearchNoteData.hexes`).
 * @property fixed occupied hexes: roots (type 1) and nodes already placed (type 2). These are never
 *   overwritten. Tags not in [graph] occupy their hex but link to nothing.
 * @property roots the root hexes; all of them must end up in one linked network.
 * @property stock units the plan may consume per aspect (player pool + table bonus).
 */
class NoteProblem(
    val grid: Set<Hex>,
    val fixed: Map<Hex, String>,
    val roots: Set<Hex>,
    val graph: AspectGraph,
    val discovered: Set<String>,
    val stock: Map<String, Int>,
    val weights: AspectWeights,
    val discount: Double,
)

class SolverOptions(
    val timeBudgetMillis: Long = 150,
    val maxAttempts: Int = 2000,
    val seed: Long = 0x7C4A5EEDL,
    /** Added to every placement; a tiny tie-breaker by default, a large value to minimise ink use. */
    val placementPenalty: Double = 1.0e-6,
)

/** Hexes to fill (in commit order) and the exact material breakdown of the whole plan. */
class Solution(val placements: Map<Hex, String>, val material: MaterialPlan, val score: Double)

/**
 * Steiner-tree heuristic over the state graph (vacant hex, aspect):
 * grow from one root's component, repeatedly run a multi-source Dijkstra from the current tree to the
 * cheapest unconnected root (node cost = marginal placement cost given everything already committed),
 * commit the path, and repeat. Each finished tree is pruned and scored exactly; restarts from other roots
 * and with perturbed costs run within the time budget and the cheapest plan wins.
 */
class NoteSolver(private val problem: NoteProblem, private val options: SolverOptions = SolverOptions()) {
    private val graph = problem.graph
    private val tagCount = graph.size
    private val hexes: List<Hex> = problem.grid.sortedWith(compareBy({ it.q }, { it.r }))
    private val hexCount = hexes.size
    private val hexIndex: Map<Hex, Int> = hexes.withIndex().associate { (i, h) -> h to i }
    private val neighbours: Array<IntArray> =
        Array(hexCount) { i -> hexes[i].neighbours().mapNotNull { hexIndex[it] }.toIntArray() }

    /** Tag index per hex: -1 vacant, [UNKNOWN] for an occupied hex whose tag is not registered. */
    private val fixedTags = IntArray(hexCount) { i ->
        val tag = problem.fixed[hexes[i]] ?: return@IntArray VACANT
        graph.indexOf(tag).takeIf { it >= 0 } ?: UNKNOWN
    }
    private val rootHexes: IntArray =
        problem.roots.mapNotNull { hexIndex[it] }.sortedWith(compareBy({ hexes[it].q }, { hexes[it].r })).toIntArray()
    private val isRoot = BooleanArray(hexCount).also { flags -> rootHexes.forEach { flags[it] = true } }
    private val model = MaterialModel(graph, problem.stock, problem.discovered, problem.weights, problem.discount)
    private val placementPenalty = maxOf(options.placementPenalty, 1.0e-9)

    // Scratch buffers reused across Dijkstra runs.
    private val dist = DoubleArray(hexCount * tagCount)
    private val prev = IntArray(hexCount * tagCount)
    private val banned = BooleanArray(hexCount * tagCount)
    private val heap = StateHeap()
    private val need = IntArray(tagCount)

    fun solve(): Solution? {
        if (rootHexes.isEmpty() || rootHexes.size != problem.roots.size) return null
        if (rootHexes.any { fixedTags[it] < 0 }) return null
        val deadline = System.nanoTime() + options.timeBudgetMillis * 1_000_000L
        val random = Random(options.seed)
        var best: Solution? = null
        var attempt = 0
        while (attempt < options.maxAttempts) {
            val start = rootHexes[attempt % rootHexes.size]
            val noise = if (attempt < rootHexes.size) null else DoubleArray(hexCount * tagCount) { 0.75 + random.nextDouble() * 0.5 }
            val candidate = attempt(start, noise)
            if (candidate != null && (best == null || candidate.score < best.score)) best = candidate
            attempt++
            if (attempt >= rootHexes.size && System.nanoTime() > deadline) break
        }
        return best
    }

    private fun attempt(startRoot: Int, noise: DoubleArray?): Solution? {
        val occupied = fixedTags.copyOf()
        val inTree = BooleanArray(hexCount)
        val placed = ArrayList<Int>()
        val direct = IntArray(tagCount)
        val network = BooleanArray(tagCount)
        for (r in rootHexes) network[fixedTags[r]] = true

        inTree[startRoot] = true
        flood(occupied, inTree)
        val placeCost = DoubleArray(tagCount)
        while (rootHexes.any { !inTree[it] }) {
            computeMarginals(direct, network, placeCost)
            val path = shortestPathToRoot(occupied, inTree, placeCost, noise) ?: return null
            for (state in path) {
                val h = state / tagCount
                val t = state % tagCount
                if (occupied[h] == VACANT) {
                    occupied[h] = t
                    placed.add(h)
                    direct[t]++
                }
                network[t] = true
                inTree[h] = true
            }
            flood(occupied, inTree)
        }

        prune(occupied, placed, direct)
        if (placed.isEmpty() && !addTrigger(occupied, placed, direct)) return null
        return finish(occupied, placed, direct)
    }

    /** Marginal penalised cost of one more placement of each aspect, given the plan so far. */
    private fun computeMarginals(direct: IntArray, network: BooleanArray, out: DoubleArray) {
        val base = model.score(direct, network, need)
        for (t in 0 until tagCount) {
            val wasNetwork = network[t]
            direct[t]++
            network[t] = true
            // A placement can lower the total (it takes the discounted stock unit a combination input was
            // using at full weight); Dijkstra needs non-negative node costs, the exact total is scored later.
            out[t] = maxOf(0.0, model.score(direct, network, need) - base)
            direct[t]--
            network[t] = wasNetwork
        }
    }

    /** Multi-source Dijkstra from every tree hex; returns the committed states (source excluded). */
    private fun shortestPathToRoot(
        occupied: IntArray,
        inTree: BooleanArray,
        placeCost: DoubleArray,
        noise: DoubleArray?,
    ): IntArray? {
        banned.fill(false)
        repeat(MAX_BAN_RETRIES) {
            val target = dijkstra(occupied, inTree, placeCost, noise) ?: return null
            val states = ArrayList<Int>()
            var s = target
            while (s >= 0) {
                states.add(s)
                s = prev[s]
            }
            states.reverse()
            // states[0] is a tree source; a vacant hex may not be used twice with different aspects.
            val seen = HashSet<Int>()
            var conflict = -1
            for (i in 1 until states.size) {
                val h = states[i] / tagCount
                if (!seen.add(h)) {
                    conflict = states[i]
                    break
                }
            }
            if (conflict < 0) return states.subList(1, states.size).toIntArray()
            banned[conflict] = true
        }
        return null
    }

    private fun dijkstra(occupied: IntArray, inTree: BooleanArray, placeCost: DoubleArray, noise: DoubleArray?): Int? {
        dist.fill(Double.POSITIVE_INFINITY)
        prev.fill(-1)
        heap.clear()
        for (h in 0 until hexCount) {
            if (inTree[h] && occupied[h] >= 0) {
                val s = h * tagCount + occupied[h]
                dist[s] = 0.0
                heap.push(0.0, s)
            }
        }
        while (heap.isNotEmpty()) {
            val d = heap.peekKey()
            val s = heap.pop()
            if (d > dist[s]) continue
            val h = s / tagCount
            val t = s % tagCount
            if (isRoot[h] && !inTree[h]) return s
            for (nb in neighbours[h]) {
                if (inTree[nb]) continue
                val nbTag = occupied[nb]
                if (nbTag >= 0) {
                    if (graph.linked(t, nbTag)) relax(s, nb * tagCount + nbTag, d)
                } else if (nbTag == VACANT) {
                    for (u in graph.linkedWith(t)) {
                        val ns = nb * tagCount + u
                        if (banned[ns]) continue
                        val c = placeCost[u] * (noise?.get(ns) ?: 1.0) + placementPenalty
                        relax(s, ns, d + c)
                    }
                }
            }
        }
        return null
    }

    private fun relax(from: Int, to: Int, d: Double) {
        if (d < dist[to]) {
            dist[to] = d
            prev[to] = from
            heap.push(d, to)
        }
    }

    /** Adds every occupied hex linked to the tree (fixed nodes and roots touched by new placements). */
    private fun flood(occupied: IntArray, inTree: BooleanArray) {
        val queue = ArrayDeque<Int>()
        for (h in 0 until hexCount) if (inTree[h]) queue.add(h)
        while (queue.isNotEmpty()) {
            val h = queue.poll()
            val t = occupied[h]
            if (t < 0) continue
            for (nb in neighbours[h]) {
                val nt = occupied[nb]
                if (!inTree[nb] && nt >= 0 && graph.linked(t, nt)) {
                    inTree[nb] = true
                    queue.add(nb)
                }
            }
        }
    }

    /** Removes placements whose absence keeps every root connected, most expensive first. */
    private fun prune(occupied: IntArray, placed: ArrayList<Int>, direct: IntArray) {
        val order = placed.sortedByDescending { problem.weights.of(graph.tags[occupied[it]]) }
        for (h in order) {
            val t = occupied[h]
            occupied[h] = VACANT
            if (rootsConnected(occupied, null)) {
                placed.remove(h)
                direct[t]--
            } else {
                occupied[h] = t
            }
        }
    }

    /**
     * The server only checks completion after a placement, so an already connected note still needs one.
     * Uses the cheapest discovered aspect on any vacant hex.
     */
    private fun addTrigger(occupied: IntArray, placed: ArrayList<Int>, direct: IntArray): Boolean {
        val vacant = (0 until hexCount).firstOrNull { occupied[it] == VACANT } ?: return false
        val network = BooleanArray(tagCount)
        for (r in rootHexes) network[fixedTags[r]] = true
        val base = model.score(direct, network, need)
        var bestTag = -1
        var bestCost = Double.POSITIVE_INFINITY
        for (t in 0 until tagCount) {
            if (!model.isDiscovered(t)) continue
            direct[t]++
            val c = model.score(direct, network, need) - base
            direct[t]--
            if (c < bestCost) {
                bestCost = c
                bestTag = t
            }
        }
        if (bestTag < 0) return false
        occupied[vacant] = bestTag
        placed.add(vacant)
        direct[bestTag]++
        return true
    }

    private fun finish(occupied: IntArray, placed: List<Int>, direct: IntArray): Solution? {
        val network = BooleanArray(tagCount)
        for (r in rootHexes) network[fixedTags[r]] = true
        for (h in placed) network[occupied[h]] = true
        var material = model.evaluate(direct, network)
        if (!rootsConnected(occupied, discoveredAfter(material))) {
            // Existing nodes in the network may be undiscovered too: discover them as well.
            for (h in componentOfRoots(occupied)) {
                val t = occupied[h]
                if (t >= 0) network[t] = true
            }
            material = model.evaluate(direct, network)
            if (!rootsConnected(occupied, discoveredAfter(material))) return null
        }
        val placements = LinkedHashMap<Hex, String>()
        for (h in placed) placements[hexes[h]] = graph.tags[occupied[h]]
        val score = model.score(direct, network, need) + placed.size * placementPenalty
        return Solution(placements, material, score)
    }

    private fun discoveredAfter(material: MaterialPlan): BooleanArray {
        val known = BooleanArray(tagCount) { model.isDiscovered(it) }
        for (tag in material.crafted.keys) known[graph.indexOf(tag)] = true
        return known
    }

    /** Whether all roots are in one linked component; [known] restricts links to discovered aspects. */
    private fun rootsConnected(occupied: IntArray, known: BooleanArray?): Boolean {
        val reached = reach(occupied, known)
        return rootHexes.all { reached[it] }
    }

    private fun componentOfRoots(occupied: IntArray): List<Int> {
        val reached = reach(occupied, null)
        return (0 until hexCount).filter { reached[it] }
    }

    private fun reach(occupied: IntArray, known: BooleanArray?): BooleanArray {
        val reached = BooleanArray(hexCount)
        val start = rootHexes[0]
        reached[start] = true
        val queue = ArrayDeque<Int>()
        queue.add(start)
        while (queue.isNotEmpty()) {
            val h = queue.poll()
            val t = occupied[h]
            if (t < 0 || (known != null && !known[t])) continue
            for (nb in neighbours[h]) {
                val nt = occupied[nb]
                if (reached[nb] || nt < 0 || (known != null && !known[nt])) continue
                if (graph.linked(t, nt)) {
                    reached[nb] = true
                    queue.add(nb)
                }
            }
        }
        return reached
    }

    /** Binary min-heap of (key, state) with lazy deletion. */
    private class StateHeap {
        private var keys = DoubleArray(1024)
        private var values = IntArray(1024)
        private var size = 0

        fun clear() {
            size = 0
        }

        fun isNotEmpty(): Boolean = size > 0

        fun peekKey(): Double = keys[0]

        fun push(key: Double, value: Int) {
            if (size == keys.size) {
                keys = keys.copyOf(size * 2)
                values = values.copyOf(size * 2)
            }
            var i = size++
            while (i > 0) {
                val parent = (i - 1) / 2
                if (keys[parent] <= key) break
                keys[i] = keys[parent]
                values[i] = values[parent]
                i = parent
            }
            keys[i] = key
            values[i] = value
        }

        fun pop(): Int {
            val top = values[0]
            size--
            if (size > 0) {
                val key = keys[size]
                val value = values[size]
                var i = 0
                while (true) {
                    var child = 2 * i + 1
                    if (child >= size) break
                    if (child + 1 < size && keys[child + 1] < keys[child]) child++
                    if (keys[child] >= key) break
                    keys[i] = keys[child]
                    values[i] = values[child]
                    i = child
                }
                keys[i] = key
                values[i] = value
            }
            return top
        }
    }

    private companion object {
        const val VACANT = -1
        const val UNKNOWN = -2
        const val MAX_BAN_RETRIES = 8
    }
}
