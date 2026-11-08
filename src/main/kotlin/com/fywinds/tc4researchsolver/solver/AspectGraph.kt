package com.fywinds.tc4researchsolver.solver

/**
 * The aspect registry as a graph. Built at runtime from `thaumcraft.api.aspects.Aspect.aspects`, whose
 * iteration (registration) order [components] must preserve.
 *
 * [components] maps every registered tag to its component tags as returned by `Aspect.getComponents()`:
 * an empty list for primals, the two component tags for compounds. A component that is not itself a
 * registered tag is dropped, which makes that aspect uncraftable but keeps its remaining link.
 *
 * Thaumcraft's link rule (`ResearchManager.checkConnections`): two adjacent aspects link when one of them
 * is a direct component of the other (and both are discovered, which callers handle separately).
 *
 * Crafting (`ResearchManager.getCombinationResult`) returns the first registered aspect whose components
 * match the pair in either order, so a later aspect with the same pair cannot be crafted.
 */
class AspectGraph(components: Map<String, List<String>>) {
    val tags: List<String> = components.keys.toList()
    val size: Int = tags.size

    private val indexByTag: Map<String, Int> = tags.withIndex().associate { (i, tag) -> tag to i }

    /** Component indices per aspect; size 2 for craftable compounds. */
    private val componentIndices: Array<IntArray> = Array(size) { i ->
        components.getValue(tags[i]).mapNotNull { indexByTag[it] }.toIntArray()
    }

    private val craftableFlags: BooleanArray = BooleanArray(size).also { flags ->
        val seenPairs = HashSet<Long>()
        for (i in 0 until size) {
            val parts = componentIndices[i]
            if (components.getValue(tags[i]).size != 2 || parts.size != 2) continue
            val pair = minOf(parts[0], parts[1]).toLong() shl 32 or maxOf(parts[0], parts[1]).toLong()
            flags[i] = seenPairs.add(pair)
        }
    }

    /** For every aspect, the aspects it links with (its components and the compounds made from it). */
    private val linkedIndices: Array<IntArray>

    /** Longest component chain down to a primal; primals have depth 0. */
    private val depths: IntArray

    init {
        val linked = Array(size) { LinkedHashSet<Int>() }
        for (i in 0 until size) {
            for (c in componentIndices[i]) {
                if (c != i) {
                    linked[i].add(c)
                    linked[c].add(i)
                }
            }
        }
        linkedIndices = Array(size) { linked[it].toIntArray() }

        depths = IntArray(size) { -1 }
        val visiting = BooleanArray(size)
        fun depthOf(i: Int): Int {
            if (depths[i] >= 0) return depths[i]
            require(!visiting[i]) { "Aspect components form a cycle at ${tags[i]}" }
            visiting[i] = true
            val d = componentIndices[i].maxOfOrNull { depthOf(it) + 1 } ?: 0
            visiting[i] = false
            depths[i] = d
            return d
        }
        for (i in 0 until size) depthOf(i)
    }

    fun indexOf(tag: String): Int = indexByTag[tag] ?: -1

    operator fun contains(tag: String): Boolean = tag in indexByTag

    fun componentsOf(index: Int): IntArray = componentIndices[index]

    fun componentsOf(tag: String): List<String> = componentIndices[indexByTag.getValue(tag)].map { tags[it] }

    fun isCraftable(index: Int): Boolean = craftableFlags[index]

    fun linkedWith(index: Int): IntArray = linkedIndices[index]

    fun depth(index: Int): Int = depths[index]

    fun linked(a: Int, b: Int): Boolean =
        componentIndices[a].contains(b) || componentIndices[b].contains(a)

    fun linked(a: String, b: String): Boolean {
        val ia = indexOf(a)
        val ib = indexOf(b)
        return ia >= 0 && ib >= 0 && linked(ia, ib)
    }
}
