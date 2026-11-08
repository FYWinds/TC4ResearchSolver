package com.fywinds.tc4researchsolver.solver

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class FeasibilityTest {
    /** Roots `p1` and `p2` with one hex between them; only `m` (= p1 + p2) links both. */
    private val components = linkedMapOf("p1" to emptyList(), "p2" to emptyList(), "m" to listOf("p1", "p2"))
    private val row = Scenario.row(3)
    private val roots = mapOf(row[0] to "p1", row[2] to "p2")

    @Test
    fun `reports the exact primal shortfall and plans nothing`() {
        val scenario = Scenario(components, row.toSet(), roots, pool = mapOf("p2" to 5))

        val outcome = scenario.plan()

        assertThat(outcome).isEqualTo(PlanOutcome.MissingAspects(mapOf("p1" to 1)))
    }

    @Test
    fun `counts the shortfall over the whole plan`() {
        // Five gaps in a row of roots p1/p2; every gap needs one m, crafted from p1 + p2.
        val row = Scenario.row(11)
        val roots = row.withIndex().filter { it.index % 2 == 0 }.associate { (i, h) -> h to if (i % 4 == 0) "p1" else "p2" }
        val scenario = Scenario(components, row.toSet(), roots, pool = mapOf("p1" to 2, "p2" to 9, "m" to 1))

        val outcome = scenario.plan()

        assertThat(outcome).isEqualTo(PlanOutcome.MissingAspects(mapOf("p1" to 2)))
    }

    @Test
    fun `reports missing ink`() {
        val scenario = Scenario(components, row.toSet(), roots, pool = mapOf("m" to 1), ink = 0)

        assertThat(scenario.plan()).isEqualTo(PlanOutcome.MissingInk(needed = 1, available = 0))
    }

    @Test
    fun `reports when no route links the roots`() {
        val scenario = Scenario(
            linkedMapOf("p1" to emptyList(), "p2" to emptyList(), "q" to emptyList(), "m" to listOf("p1", "p2")),
            row.toSet(), roots, placed = mapOf(row[1] to "q"), pool = mapOf("m" to 5),
        )

        assertThat(scenario.plan()).isEqualTo(PlanOutcome.NoRoute)
    }

    @Test
    fun `an already linked note still gets one placement so the server checks completion`() {
        val row = Scenario.row(3)
        val scenario = Scenario(
            components, row.toSet(), mapOf(row[0] to "p1", row[1] to "m"), pool = mapOf("p1" to 1, "p2" to 1),
        )

        val ready = scenario.plan().ready()

        assertThat(ready.placements).hasSize(1)
        scenario.assertSolves(ready)
    }
}
