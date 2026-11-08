package com.fywinds.tc4researchsolver.solver

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AspectWeightsTest {
    @Test
    fun `matches tags case-insensitively and defaults unknown aspects to 16`() {
        val (weights, rejected) = AspectWeights.parse(listOf("Ordo=1", " AER = 4 ", "Bad", "x=", "=3", "neg=-1"))

        assertThat(weights.of("ordo")).isEqualTo(1.0)
        assertThat(weights.of("Aer")).isEqualTo(4.0)
        assertThat(weights.of("evolutio")).isEqualTo(16.0)
        assertThat(rejected).containsExactly("Bad", "x=", "=3", "neg=-1")
    }

    @Test
    fun `default table covers every Thaumcraft and GTNH aspect of the pack except unlisted ones`() {
        val (weights, rejected) = AspectWeights.parse(AspectWeights.DEFAULT_ENTRIES)
        val explicit = AspectWeights.DEFAULT_ENTRIES.map { it.substringBefore('=') }.toSet()

        assertThat(rejected).isEmpty()
        assertThat(Aspects.GTNH_COMPONENTS.keys - explicit).containsExactly("evolutio")
        assertThat(weights.of("custom1")).isEqualTo(weights.of("aequalitas"))
    }

    @Test
    fun `aspects sharing a component pair are only craftable for the first registered one`() {
        val graph = AspectGraph(linkedMapOf("a" to emptyList(), "b" to emptyList(), "first" to listOf("a", "b"), "second" to listOf("b", "a")))

        assertThat(graph.isCraftable(graph.indexOf("first"))).isTrue()
        assertThat(graph.isCraftable(graph.indexOf("second"))).isFalse()
        assertThat(graph.linked("second", "a")).isTrue()
    }
}
