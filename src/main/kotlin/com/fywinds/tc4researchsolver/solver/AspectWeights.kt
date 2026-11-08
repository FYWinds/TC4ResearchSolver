package com.fywinds.tc4researchsolver.solver

import java.util.Locale

/** Per-aspect weights (lower is cheaper). Tags match case-insensitively; unknown tags weigh [defaultWeight]. */
class AspectWeights(entries: Map<String, Double>, val defaultWeight: Double = DEFAULT_WEIGHT) {
    private val byTag: Map<String, Double> = entries.mapKeys { it.key.lowercase(Locale.ROOT) }

    fun of(tag: String): Double = byTag[tag.lowercase(Locale.ROOT)] ?: defaultWeight

    companion object {
        const val DEFAULT_WEIGHT: Double = 16.0

        /**
         * The GTNH wiki table (`Data:神秘时代研究助手.tabx`). GregTech registers five of its aspects under
         * the tags `custom1`..`custom5` (`gregtech.common.GTThaumcraftCompat`), so those tags are listed
         * with the weights of their display names.
         */
        val DEFAULT_ENTRIES: List<String> = listOf(
            "aequalitas=512", "aer=4", "alienis=8", "aqua=2", "arbor=4", "astrum=512", "auram=64",
            "bestia=64", "caelum=512", "cognitio=4", "corpus=16", "desidia=64", "electrum=1", "exanimis=16",
            "fabrico=1", "fames=64", "gelum=64", "gloria=512", "gula=16", "herba=4", "humanus=64",
            "ignis=4", "infernus=64", "instrumentum=1", "invidia=512", "ira=64", "iter=4", "limus=16",
            "lucrum=4", "lux=8", "luxuria=512", "machina=1", "magneto=16", "messis=16", "metallum=1",
            "meto=64", "mortuus=64", "motus=4", "nebrisum=64", "ordo=1", "pannus=8", "perditio=2",
            "perfodio=64", "permutatio=16", "potentia=2", "praecantatio=8", "primordium=512", "radio=64",
            "sano=64", "sensus=4", "spiritus=64", "strontio=64", "superbia=64", "tabernus=64", "telum=16",
            "tempestas=64", "tempus=64", "tenebrae=16", "terminus=512", "terra=4", "tutamen=4",
            "vacuos=8", "venenum=64", "vesania=512", "victus=8", "vinculum=16", "vitium=64", "vitreus=2",
            "volatus=8",
            // GregTech's real tags for Aequalitas, Vesania, Primordium, Astrum, Gloria.
            "custom1=512", "custom2=512", "custom3=512", "custom4=512", "custom5=512",
        )

        /** Parses `tag=weight` lines; returns the weights and the lines that could not be parsed. */
        fun parse(lines: List<String>): Pair<AspectWeights, List<String>> {
            val entries = LinkedHashMap<String, Double>()
            val rejected = ArrayList<String>()
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val eq = line.indexOf('=')
                val tag = if (eq > 0) line.substring(0, eq).trim() else ""
                val weight = if (eq > 0) line.substring(eq + 1).trim().toDoubleOrNull() else null
                if (tag.isEmpty() || weight == null || weight < 0.0 || weight.isNaN() || weight.isInfinite()) {
                    rejected.add(raw)
                } else {
                    entries[tag] = weight
                }
            }
            return AspectWeights(entries) to rejected
        }
    }
}
