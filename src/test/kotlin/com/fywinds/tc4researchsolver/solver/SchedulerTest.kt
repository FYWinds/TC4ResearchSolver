package com.fywinds.tc4researchsolver.solver

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Random

class SchedulerTest {
    private val components = Aspects.GTNH_COMPONENTS
    private val allTags = components.keys.toList()

    @Test
    fun `never sends a packet the worst-case server would refuse, even with scarce stock`() {
        val random = Random(99)
        var ready = 0
        repeat(150) {
            val radius = 2 + random.nextInt(4)
            val rootCount = 2 + random.nextInt(SyntheticNote.maxRoots(radius) - 1)
            val note = SyntheticNote.generate(radius, allTags.shuffled(random).take(rootCount), random)
            val discovered = allTags.filter { it in Aspects.PRIMAL_TAGS || random.nextInt(3) > 0 }.toSet()
            val pool = discovered.associateWith { if (it in Aspects.PRIMAL_TAGS) random.nextInt(25) else random.nextInt(3) }
            val bonus = allTags.filter { random.nextInt(6) == 0 }.associateWith { 1 }
            val scenario = Scenario(
                components, note.grid, note.roots, pool = pool, bonus = bonus, discovered = discovered,
                ink = 5 + random.nextInt(40),
            )

            val outcome = scenario.plan()

            if (outcome is PlanOutcome.Ready) {
                ready++
                scenario.assertSolves(outcome)
            } else {
                assertThat(outcome).isInstanceOfAny(
                    PlanOutcome.MissingAspects::class.java, PlanOutcome.MissingInk::class.java, PlanOutcome.NoRoute::class.java,
                )
            }
        }
        assertThat(ready).describedAs("scenarios that were feasible").isGreaterThan(10)
    }

    @Test
    fun `takes inputs from the table bonus with the bonus flag when the pool is empty`() {
        val graph = AspectGraph(linkedMapOf("a" to emptyList(), "b" to emptyList(), "c" to listOf("a", "b")))
        val state = AspectState(pool = mapOf("a" to 0, "b" to 1), bonus = mapOf("a" to 1, "b" to 1), discovered = setOf("a", "b", "c"))

        val result = PacketScheduler.schedule(graph, mapOf("c" to 1), mapOf(Hex(0, 0) to "c"), state, ink = 1)

        assertThat(result).isEqualTo(
            PacketScheduler.Result.Scheduled(
                listOf(Action.Combine("a", "b", firstFromBonus = true, secondFromBonus = true, result = "c"), Action.Place(Hex(0, 0), "c")),
            ),
        )
    }

    @Test
    fun `refuses a plan whose inputs are not covered instead of sending part of it`() {
        val graph = AspectGraph(linkedMapOf("a" to emptyList(), "b" to emptyList(), "c" to listOf("a", "b")))
        val state = AspectState(pool = mapOf("a" to 1, "b" to 1), bonus = emptyMap(), discovered = setOf("a", "b", "c"))

        val twoCrafts = PacketScheduler.schedule(graph, mapOf("c" to 2), emptyMap(), state, ink = 10)
        val noInk = PacketScheduler.schedule(graph, mapOf("c" to 1), mapOf(Hex(0, 0) to "c"), state, ink = 0)
        val uncoveredPlacement = PacketScheduler.schedule(graph, emptyMap(), mapOf(Hex(0, 0) to "c"), state, ink = 10)

        assertThat(listOf(twoCrafts, noInk, uncoveredPlacement)).allMatch { it is PacketScheduler.Result.Uncovered }
    }

    @Test
    fun `orders combinations so every input exists before it is used`() {
        // Terminus = lucrum + alienis, both several steps above the primals.
        val graph = AspectGraph(components)
        val crafted = mapOf("terminus" to 1, "lucrum" to 1, "alienis" to 1, "humanus" to 1, "fames" to 1, "tenebrae" to 1)
        val pool = mapOf("bestia" to 1, "cognitio" to 1, "victus" to 1, "vacuos" to 3, "lux" to 1)
        val state = AspectState(pool, emptyMap(), allTags.toSet())
        // Requested in reverse dependency order on purpose.
        val result = PacketScheduler.schedule(graph, crafted.entries.reversed().associate { it.toPair() }, emptyMap(), state, ink = 0)

        assertThat(result).isInstanceOf(PacketScheduler.Result.Scheduled::class.java)
        val results = (result as PacketScheduler.Result.Scheduled).actions.map { (it as Action.Combine).result }
        assertThat(results.indexOf("terminus")).isGreaterThan(results.indexOf("lucrum")).isGreaterThan(results.indexOf("alienis"))
    }
}
