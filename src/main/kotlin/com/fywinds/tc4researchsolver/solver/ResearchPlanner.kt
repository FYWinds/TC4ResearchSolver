package com.fywinds.tc4researchsolver.solver

/** Everything the planner needs, read from the client's latest synced state. */
class PlanInput(
    val grid: Set<Hex>,
    val fixed: Map<Hex, String>,
    val roots: Set<Hex>,
    val graph: AspectGraph,
    val state: AspectState,
    /** Placements the scribing tools can still pay for (one durability each). */
    val ink: Int,
    val weights: AspectWeights,
    val discount: Double,
)

sealed interface PlanOutcome {
    /** Send [actions] in order. */
    data class Ready(val actions: List<Action>, val placements: Map<Hex, String>, val cost: Double) : PlanOutcome {
        val combinations: Int get() = actions.count { it is Action.Combine }
    }

    /** Not enough of these (uncraftable) aspects, in units, for the cheapest plan found. */
    data class MissingAspects(val shortfall: Map<String, Int>) : PlanOutcome

    data class MissingInk(val needed: Int, val available: Int) : PlanOutcome

    /** The grid offers no way to link every root (blocked by placed nodes or unknown aspects). */
    data object NoRoute : PlanOutcome

    /** The simulation refused a packet of the plan; nothing may be sent. */
    data class Uncovered(val blocked: String) : PlanOutcome
}

object ResearchPlanner {
    /** Per-placement penalty for the retry that minimises ink use; dominates any aspect cost. */
    private const val INK_SAVING_PENALTY = 1.0e5

    fun plan(input: PlanInput, options: SolverOptions = SolverOptions()): PlanOutcome {
        var solution = solve(input, options) ?: return PlanOutcome.NoRoute
        if (!solution.material.feasible) return PlanOutcome.MissingAspects(solution.material.shortfall)
        if (solution.placements.size > input.ink) {
            val frugal = solve(input, options.withPenalty(INK_SAVING_PENALTY))
            if (frugal != null && frugal.material.feasible && frugal.placements.size < solution.placements.size) {
                solution = frugal
            }
            if (solution.placements.size > input.ink) {
                return PlanOutcome.MissingInk(solution.placements.size, input.ink)
            }
        }
        return when (val scheduled = PacketScheduler.schedule(
            input.graph, solution.material.crafted, solution.placements, input.state, input.ink,
        )) {
            is PacketScheduler.Result.Scheduled ->
                PlanOutcome.Ready(scheduled.actions, solution.placements, solution.material.cost)
            is PacketScheduler.Result.Uncovered -> PlanOutcome.Uncovered(scheduled.blocked)
        }
    }

    private fun solve(input: PlanInput, options: SolverOptions): Solution? {
        val stock = HashMap<String, Int>()
        for ((tag, n) in input.state.pool) if (n > 0) stock.merge(tag, n, Int::plus)
        for ((tag, n) in input.state.bonus) if (n > 0) stock.merge(tag, n, Int::plus)
        val problem = NoteProblem(
            input.grid, input.fixed, input.roots, input.graph, input.state.discovered, stock,
            input.weights, input.discount,
        )
        return NoteSolver(problem, options).solve()
    }

    private fun SolverOptions.withPenalty(penalty: Double) =
        SolverOptions(timeBudgetMillis, maxAttempts, seed, penalty)
}
