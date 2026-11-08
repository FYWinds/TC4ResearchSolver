package com.fywinds.tc4researchsolver.solver

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Random

class RandomNotesTest {
    private val components = Aspects.GTNH_COMPONENTS
    private val allTags = components.keys.toList()
    private val compounds = allTags - Aspects.PRIMAL_TAGS.toSet()

    @Test
    fun `solves notes of radius 2 to 5 with 2 to 8 roots, GTNH aspects included`() {
        val random = Random(20261107)
        for (radius in 2..5) for (rootCount in 2..SyntheticNote.maxRoots(radius)) repeat(3) {
            val note = SyntheticNote.generate(
                radius, allTags.shuffled(random).take(rootCount), random,
                blanks = if (radius >= 3) radius * 2 else 0,
            )
            val pool = allTags.associateWith { if (it in Aspects.PRIMAL_TAGS) 500 else random.nextInt(4) }
            val scenario = Scenario(components, note.grid, note.roots, pool = pool)

            val ready = scenario.plan().ready()

            assertThat(ready.placements.keys).allMatch { it in note.grid && it !in note.roots }
            scenario.assertSolves(ready)
        }
    }

    @Test
    fun `solves notes whose roots and paths need undiscovered aspects`() {
        val random = Random(77)
        for (radius in 2..5) repeat(6) {
            val rootCount = 2 + random.nextInt(SyntheticNote.maxRoots(radius) - 1)
            val note = SyntheticNote.generate(radius, allTags.shuffled(random).take(rootCount), random)
            val discovered = Aspects.PRIMAL_TAGS.toSet() + compounds.filter { random.nextBoolean() }
            val pool = Aspects.PRIMAL_TAGS.associateWith { 500 } + discovered.filter { it in compounds }.associateWith { random.nextInt(3) }
            val scenario = Scenario(components, note.grid, note.roots, pool = pool, discovered = discovered)

            val ready = scenario.plan().ready()

            val server = scenario.assertSolves(ready)
            assertThat(server.discovered).containsAll(note.roots.values)
        }
    }

    @Test
    fun `aspects in the plan never exceed what the stock and the combinations provide`() {
        val random = Random(5)
        repeat(40) {
            val radius = 2 + random.nextInt(4)
            val rootCount = 2 + random.nextInt(SyntheticNote.maxRoots(radius) - 1)
            val note = SyntheticNote.generate(radius, allTags.shuffled(random).take(rootCount), random)
            val pool = allTags.associateWith { if (it in Aspects.PRIMAL_TAGS) 20 + random.nextInt(40) else random.nextInt(3) }
            val bonus = allTags.filter { random.nextInt(8) == 0 }.associateWith { 1 }
            val scenario = Scenario(components, note.grid, note.roots, pool = pool, bonus = bonus)

            when (val outcome = scenario.plan()) {
                is PlanOutcome.Ready -> scenario.assertSolves(outcome)
                is PlanOutcome.MissingAspects -> assertThat(outcome.shortfall).isNotEmpty.allSatisfy { tag, n ->
                    assertThat(tag).isIn(Aspects.PRIMAL_TAGS)
                    assertThat(n).isPositive()
                }
                // A few crowded notes have no route at all; then nothing is planned either.
                else -> assertThat(outcome).isEqualTo(PlanOutcome.NoRoute)
            }
        }
    }
}
