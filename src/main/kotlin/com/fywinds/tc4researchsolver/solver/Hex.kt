package com.fywinds.tc4researchsolver.solver

/** Axial hex coordinate, identical to `thaumcraft.common.lib.utils.HexUtils.Hex` (q, r). */
data class Hex(val q: Int, val r: Int) {
    fun neighbours(): List<Hex> = DIRECTIONS.map { (dq, dr) -> Hex(q + dq, r + dr) }

    /** Same format as `HexUtils.Hex.toString()`, the key of `ResearchNoteData.hexEntries`. */
    override fun toString(): String = "$q:$r"

    companion object {
        /** Same order as `HexUtils.NEIGHBOURS`. */
        val DIRECTIONS: List<Pair<Int, Int>> = listOf(1 to 0, 1 to -1, 0 to -1, -1 to 0, -1 to 1, 0 to 1)
    }
}
