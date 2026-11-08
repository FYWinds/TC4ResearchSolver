package com.fywinds.tc4researchsolver.solver

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class CostModelTest {
    /**
     * Roots `p1` and `p2` at both ends of a row of four hexes; two two-hex routes connect them:
     * `p1 - z1 - w1 - p2` and `p1 - z2 - w2 - p2`.
     */
    private val twoRoutes = linkedMapOf(
        "p1" to emptyList(), "p2" to emptyList(), "p3" to emptyList(), "p4" to emptyList(),
        "z1" to listOf("p1", "p3"), "w1" to listOf("p2", "z1"),
        "z2" to listOf("p1", "p4"), "w2" to listOf("p2", "z2"),
    )
    private val row = Scenario.row(4)
    private val roots = mapOf(row[0] to "p1", row[3] to "p2")

    private fun weights(vararg entries: String) = AspectWeights.parse(entries.toList()).first

    @Test
    fun `prefers low-weight aspects`() {
        val scenario = Scenario(
            twoRoutes, row.toSet(), roots,
            pool = mapOf("z1" to 5, "w1" to 5, "z2" to 5, "w2" to 5),
            weights = weights("z1=100", "w1=100", "z2=1", "w2=1"),
        )

        val ready = scenario.plan().ready()

        assertThat(ready.cost).isCloseTo((1 + 1) * 0.5, within(1e-9))
        scenario.assertSolves(ready)
    }

    @Test
    fun `prefers aspects in stock over crafting`() {
        val scenario = Scenario(
            twoRoutes, row.toSet(), roots,
            pool = mapOf("p1" to 10, "p2" to 10, "p3" to 10, "p4" to 10, "z2" to 1, "w2" to 1),
            weights = weights("p1=4", "p2=4", "p3=4", "p4=4", "z1=4", "w1=4", "z2=4", "w2=4"),
        )

        val ready = scenario.plan().ready()

        assertThat(ready.combinations).isZero()
        assertThat(ready.cost).isCloseTo(2 * 4 * 0.5, within(1e-9))
        scenario.assertSolves(ready)
    }

    @Test
    fun `crafting costs the full input weights, so stocked placements win over an equal-weight craft`() {
        // Route 1 (z1, w1) is fully stocked: 2 x 4 x 0.5 = 4. Route 2 has w2 stocked and needs z2 crafted
        // from p1 + p4 (weight 2 each). Discounting the inputs too would tie both routes at 4; at full
        // input weight route 2 costs 2 + 4 = 6.
        val scenario = Scenario(
            twoRoutes, row.toSet(), roots,
            pool = mapOf("p1" to 10, "p2" to 10, "p3" to 10, "p4" to 10, "z1" to 1, "w1" to 1, "w2" to 1),
            weights = weights("p1=2", "p2=2", "p3=2", "p4=2", "z1=4", "w1=4", "z2=4", "w2=4"),
        )

        val ready = scenario.plan().ready()

        assertThat(ready.combinations).isZero()
        assertThat(ready.cost).isCloseTo(2 * 4 * 0.5, within(1e-9))
        scenario.assertSolves(ready)
    }

    @Test
    fun `accounts for stock depletion across the whole plan`() {
        // Roots r1 - gap - r2 - gap - r3; each gap takes x or y (components of every root). x is cheap but
        // only one unit exists and it cannot be crafted, so the second gap must use y.
        val components = linkedMapOf(
            "x" to emptyList(), "y" to emptyList(),
            "r1" to listOf("x", "y"), "r2" to listOf("x", "y"), "r3" to listOf("x", "y"),
        )
        val row = Scenario.row(5)
        val scenario = Scenario(
            components, row.toSet(), mapOf(row[0] to "r1", row[2] to "r2", row[4] to "r3"),
            pool = mapOf("x" to 1, "y" to 5),
            weights = weights("x=1", "y=10"),
        )

        val ready = scenario.plan().ready()

        assertThat(ready.placements.values.count { it == "x" }).isLessThanOrEqualTo(1)
        assertThat(ready.cost).isCloseTo((1 + 10) * 0.5, within(1e-9))
        scenario.assertSolves(ready)
    }

    @Test
    fun `material plan discounts placed stock and charges full weight for crafting inputs`() {
        val graph = AspectGraph(linkedMapOf("a" to emptyList(), "b" to emptyList(), "c" to listOf("a", "b")))
        val model = MaterialModel(graph, mapOf("a" to 5, "b" to 1, "c" to 1), graph.tags.toSet(), weights("a=2", "b=6", "c=10"), 0.5)
        val demand = IntArray(graph.size).also { it[graph.indexOf("c")] = 3 }

        val plan = model.evaluate(demand, BooleanArray(graph.size))

        assertThat(plan.used).containsEntry("c", 1).containsEntry("a", 2)
        assertThat(plan.crafted).containsEntry("c", 2)
        assertThat(plan.shortfall).containsExactly(org.assertj.core.api.Assertions.entry("b", 1))
        // One c placed from stock (10 x 0.5); two c crafted from two a (2 each) and the one b in stock (6).
        assertThat(plan.cost).isCloseTo(10 * 0.5 + 2 * 2 + 6, within(1e-9))
    }

    @Test
    fun `an undiscovered network aspect is crafted once even when stock covers it`() {
        val graph = AspectGraph(linkedMapOf("a" to emptyList(), "b" to emptyList(), "c" to listOf("a", "b")))
        val model = MaterialModel(graph, mapOf("a" to 3, "b" to 3, "c" to 3), setOf("a", "b"), weights(), 0.5)
        val demand = IntArray(graph.size).also { it[graph.indexOf("c")] = 2 }
        val network = BooleanArray(graph.size).also { it[graph.indexOf("c")] = true }

        val plan = model.evaluate(demand, network)

        assertThat(plan.crafted).containsEntry("c", 1)
        assertThat(plan.used).containsEntry("c", 1).containsEntry("a", 1).containsEntry("b", 1)
        assertThat(plan.feasible).isTrue()
    }

    @Test
    fun `uses nodes already on the note`() {
        val row = Scenario.row(4)
        val components = linkedMapOf("p1" to emptyList(), "p2" to emptyList(), "m" to listOf("p1", "p2"), "n" to listOf("m", "p2"))
        val scenario = Scenario(
            components, row.toSet(), mapOf(row[0] to "p1", row[3] to "p2"), placed = mapOf(row[1] to "m"),
            pool = mapOf("n" to 2),
        )

        val ready = scenario.plan().ready()

        assertThat(ready.placements).hasSize(1)
        scenario.assertSolves(ready)
    }
}
