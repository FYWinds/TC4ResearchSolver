package com.fywinds.tc4researchsolver.solver

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Random

class DiscoveryTest {
    private val components = Aspects.GTNH_COMPONENTS
    private val primalsOnly = Aspects.PRIMAL_TAGS.toSet()
    private val primalPool = Aspects.PRIMAL_TAGS.associateWith { 200 }

    @Test
    fun `discovers unknown root aspects before placing`() {
        // Astrum (GregTech tag custom4 = lux + custom3) and Terminus are unknown to a player who only
        // knows the primals; both show as "?" on the note.
        val note = SyntheticNote.generate(3, listOf("custom4", "terminus", "aer"), Random(1))
        val scenario = Scenario(components, note.grid, note.roots, pool = primalPool, discovered = primalsOnly)

        val ready = scenario.plan().ready()

        val combinedResults = ready.actions.filterIsInstance<Action.Combine>().map { it.result }
        assertThat(combinedResults).contains("custom4", "terminus")
        val server = scenario.assertSolves(ready)
        assertThat(server.discovered).contains("custom4", "terminus")
    }

    @Test
    fun `every aspect placed is discovered by the time the note is checked`() {
        val note = SyntheticNote.generate(4, listOf("ira", "tabernus", "electrum", "custom5"), Random(2))
        val scenario = Scenario(components, note.grid, note.roots, pool = primalPool, discovered = primalsOnly)

        val ready = scenario.plan().ready()

        val server = scenario.assertSolves(ready)
        assertThat(server.discovered).containsAll(ready.placements.values)
    }

    @Test
    fun `a discovered aspect with no units is crafted like any other`() {
        val note = SyntheticNote.generate(2, listOf("metallum", "vitreus"), Random(3))
        val scenario = Scenario(components, note.grid, note.roots, pool = mapOf("terra" to 10, "ordo" to 10))

        val ready = scenario.plan().ready()

        scenario.assertSolves(ready)
    }
}
