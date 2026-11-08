package com.fywinds.tc4researchsolver.solver

import org.assertj.core.api.Assertions.assertThat

/** Builds planner inputs and replays plans against [ThaumcraftServerModel]. */
class Scenario(
    val components: Map<String, List<String>>,
    val grid: Set<Hex>,
    val roots: Map<Hex, String>,
    val placed: Map<Hex, String> = emptyMap(),
    val pool: Map<String, Int>,
    val bonus: Map<String, Int> = emptyMap(),
    val discovered: Set<String> = components.keys,
    val ink: Int = 1000,
    val weights: AspectWeights = AspectWeights.parse(AspectWeights.DEFAULT_ENTRIES).first,
    val discount: Double = 0.5,
) {
    val graph = AspectGraph(components)

    fun input() = PlanInput(
        grid = grid,
        fixed = roots + placed,
        roots = roots.keys,
        graph = graph,
        state = AspectState(pool, bonus, discovered),
        ink = ink,
        weights = weights,
        discount = discount,
    )

    fun plan(options: SolverOptions = FAST): PlanOutcome = ResearchPlanner.plan(input(), options)

    fun server(discoveryBonus: Boolean = false) = ThaumcraftServerModel(
        components, grid, roots + placed, roots.keys, pool, bonus, discovered, ink, discoveryBonus,
    )

    /** Replays [ready] in the worst-case server and asserts every packet is covered and the note completes. */
    fun assertSolves(ready: PlanOutcome.Ready): ThaumcraftServerModel {
        val server = server()
        server.apply(ready.actions)
        assertThat(server.violations).describedAs("packets the server would refuse or not charge").isEmpty()
        assertThat(server.complete).describedAs("note complete per Thaumcraft's rule").isTrue()
        val combos = ready.actions.indexOfLast { it is Action.Combine }
        val firstPlace = ready.actions.indexOfFirst { it is Action.Place }
        if (combos >= 0 && firstPlace >= 0) assertThat(combos).isLessThan(firstPlace)
        return server
    }

    companion object {
        val FAST = SolverOptions(timeBudgetMillis = 30)

        /** A straight row of hexes (q = 0..n-1, r = 0). */
        fun row(n: Int): List<Hex> = (0 until n).map { Hex(it, 0) }
    }
}

fun PlanOutcome.ready(): PlanOutcome.Ready {
    assertThat(this).isInstanceOf(PlanOutcome.Ready::class.java)
    return this as PlanOutcome.Ready
}
