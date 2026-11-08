package com.fywinds.tc4researchsolver.solver

import java.util.Random

/** Aspect registries used by the tests. Components copied from the decompiled registrations. */
object Aspects {
    private val PRIMALS = listOf("aer", "terra", "ignis", "aqua", "ordo", "perditio")

    /** `thaumcraft.api.aspects.Aspect` static initialiser, Thaumcraft 4.2.3.5. */
    private val THAUMCRAFT = linkedMapOf(
        "vacuos" to listOf("aer", "perditio"), "lux" to listOf("aer", "ignis"),
        "tempestas" to listOf("aer", "aqua"), "motus" to listOf("aer", "ordo"),
        "gelum" to listOf("ignis", "perditio"), "vitreus" to listOf("terra", "ordo"),
        "victus" to listOf("aqua", "terra"), "venenum" to listOf("aqua", "perditio"),
        "potentia" to listOf("ordo", "ignis"), "permutatio" to listOf("perditio", "ordo"),
        "metallum" to listOf("terra", "vitreus"), "mortuus" to listOf("victus", "perditio"),
        "volatus" to listOf("aer", "motus"), "tenebrae" to listOf("vacuos", "lux"),
        "spiritus" to listOf("victus", "mortuus"), "sano" to listOf("victus", "ordo"),
        "iter" to listOf("motus", "terra"), "alienis" to listOf("vacuos", "tenebrae"),
        "praecantatio" to listOf("vacuos", "potentia"), "auram" to listOf("praecantatio", "aer"),
        "vitium" to listOf("praecantatio", "perditio"), "limus" to listOf("victus", "aqua"),
        "herba" to listOf("victus", "terra"), "arbor" to listOf("aer", "herba"),
        "bestia" to listOf("motus", "victus"), "corpus" to listOf("mortuus", "bestia"),
        "exanimis" to listOf("motus", "mortuus"), "cognitio" to listOf("ignis", "spiritus"),
        "sensus" to listOf("aer", "spiritus"), "humanus" to listOf("bestia", "cognitio"),
        "messis" to listOf("herba", "humanus"), "perfodio" to listOf("humanus", "terra"),
        "instrumentum" to listOf("humanus", "ordo"), "meto" to listOf("messis", "instrumentum"),
        "telum" to listOf("instrumentum", "ignis"), "tutamen" to listOf("instrumentum", "terra"),
        "fames" to listOf("victus", "vacuos"), "lucrum" to listOf("humanus", "fames"),
        "fabrico" to listOf("humanus", "instrumentum"), "pannus" to listOf("instrumentum", "bestia"),
        "machina" to listOf("motus", "instrumentum"), "vinculum" to listOf("motus", "perditio"),
    )

    /** Aspects added by mods on the ETO pack (GTNH 2.9.0), with their real tags. */
    private val GTNH_EXTRAS = linkedMapOf(
        // gregtech.common.GTThaumcraftCompat
        "strontio" to listOf("cognitio", "perditio"), "nebrisum" to listOf("perfodio", "lucrum"),
        "electrum" to listOf("potentia", "machina"), "magneto" to listOf("metallum", "iter"),
        "radio" to listOf("lux", "potentia"), "custom1" to listOf("cognitio", "ordo"),
        "custom2" to listOf("cognitio", "vitium"), "custom3" to listOf("vacuos", "motus"),
        "custom4" to listOf("lux", "custom3"), "custom5" to listOf("humanus", "iter"),
        // fox.spiteful.forbidden.DarkAspects
        "luxuria" to listOf("corpus", "fames"), "infernus" to listOf("ignis", "praecantatio"),
        "superbia" to listOf("volatus", "vacuos"), "gula" to listOf("fames", "vacuos"),
        "invidia" to listOf("sensus", "fames"), "desidia" to listOf("vinculum", "spiritus"),
        "ira" to listOf("telum", "ignis"),
        // Avaritia, Magic Bees, Thaumic Boots, Twist Space Technology
        "terminus" to listOf("lucrum", "alienis"), "tempus" to listOf("vacuos", "ordo"),
        "caelum" to listOf("vitreus", "metallum"), "tabernus" to listOf("tutamen", "iter"),
        "evolutio" to listOf("victus", "permutatio"),
    )

    val GTNH_COMPONENTS: Map<String, List<String>> = LinkedHashMap<String, List<String>>().apply {
        PRIMALS.forEach { put(it, emptyList()) }
        putAll(THAUMCRAFT)
        putAll(GTNH_EXTRAS)
    }

    val PRIMAL_TAGS: List<String> = PRIMALS

    fun gtnh(): AspectGraph = AspectGraph(GTNH_COMPONENTS)
}

/** Hex helpers mirroring `HexUtils` independently of the production [Hex]. */
object NoteGeometry {
    private val NEIGHBOURS = arrayOf(intArrayOf(1, 0), intArrayOf(1, -1), intArrayOf(0, -1), intArrayOf(-1, 0), intArrayOf(-1, 1), intArrayOf(0, 1))

    fun neighbour(h: Hex, direction: Int): Hex = Hex(h.q + NEIGHBOURS[direction][0], h.r + NEIGHBOURS[direction][1])

    /** `HexUtils.generateHexes(radius)`. */
    fun disc(radius: Int): Set<Hex> {
        val result = LinkedHashSet<Hex>()
        for (q in -radius..radius) for (r in -radius..radius) {
            if (maxOf(Math.abs(q), Math.abs(r), Math.abs(q + r)) <= radius) result.add(Hex(q, r))
        }
        return result
    }

    /** `HexUtils.getRing(radius)`. */
    fun ring(radius: Int): List<Hex> {
        var h = Hex(0, 0)
        repeat(radius) { h = neighbour(h, 4) }
        val ring = ArrayList<Hex>()
        for (i in 0 until 6) for (j in 0 until radius) {
            ring.add(h)
            h = neighbour(h, i)
        }
        return ring
    }
}

/** A research note like `ResearchManager.createNote` makes: roots spread on the outer ring, optional blanks. */
class SyntheticNote(val grid: Set<Hex>, val roots: Map<Hex, String>) {
    companion object {
        /**
         * Root count cap per radius. Thaumcraft sizes the grid by complexity, so crowded notes (8 roots on
         * a radius-2 ring) do not occur; some of them have no solution at all (checked with an exact ILP).
         */
        fun maxRoots(radius: Int): Int = minOf(8, 2 * radius + 2)

        fun generate(radius: Int, rootTags: List<String>, random: Random, blanks: Int = 0): SyntheticNote {
            val ring = NoteGeometry.ring(radius)
            val spacing = ring.size.toFloat() / rootTags.size
            val roots = LinkedHashMap<Hex, String>()
            var pos = 0.0f
            for (tag in rootTags) {
                roots[ring[Math.round(pos) % ring.size]] = tag
                pos += spacing
            }
            val grid = LinkedHashSet(NoteGeometry.disc(radius))
            var left = blanks
            var guard = 0
            while (left > 0 && guard++ < 10_000) {
                val candidate = grid.elementAt(random.nextInt(grid.size))
                if (candidate in roots) continue
                // Like createNote, but stricter so the note stays solvable-shaped: every root keeps two
                // vacant neighbours and the vacant hexes stay one connected region.
                val after = grid - candidate
                val rootsKeepAccess = (0 until 6).map { NoteGeometry.neighbour(candidate, it) }
                    .filter { it in roots }
                    .all { root -> (0 until 6).count { d -> NoteGeometry.neighbour(root, d).let { it in after && it !in roots } } >= 2 }
                if (rootsKeepAccess && vacantConnected(after, roots.keys)) {
                    grid.remove(candidate)
                    left--
                }
            }
            return SyntheticNote(grid, roots)
        }

        private fun vacantConnected(grid: Set<Hex>, roots: Set<Hex>): Boolean {
            val vacant = grid - roots
            val start = vacant.firstOrNull() ?: return true
            val seen = hashSetOf(start)
            val queue = ArrayDeque(listOf(start))
            while (queue.isNotEmpty()) {
                val h = queue.removeFirst()
                for (d in 0 until 6) {
                    val n = NoteGeometry.neighbour(h, d)
                    if (n in vacant && seen.add(n)) queue.add(n)
                }
            }
            return seen.size == vacant.size
        }
    }
}
