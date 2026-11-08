package com.fywinds.tc4researchsolver

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guards the dedicated-server path: the mod class may reach client code only through ClientBootstrap,
 * the solver is free of Minecraft types, and every client class is @SideOnly(CLIENT).
 * Reads compiled class files without loading them.
 */
class ClientIsolationTest {
    private val classesRoot: File = File(
        javaClass.classLoader.getResource("com/fywinds/tc4researchsolver/TC4ResearchSolver.class")!!.toURI(),
    ).parentFile

    private fun classFiles(dir: String): List<File> =
        File(classesRoot, dir).walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.toList()

    private fun constants(file: File): String = String(file.readBytes(), Charsets.ISO_8859_1)

    @Test
    fun `mod class references no client classes except the bootstrap`() {
        val modClasses = classesRoot.listFiles()!!.filter { it.isFile && it.name.endsWith(".class") }
        assertThat(modClasses).isNotEmpty
        for (file in modClasses) {
            val text = constants(file)
            assertThat(text).describedAs(file.name).doesNotContain("net/minecraft/client/", "thaumcraft/client/")
            val clientRefs = Regex("com/fywinds/tc4researchsolver/client/[A-Za-z0-9_$]+").findAll(text).map { it.value }.toSet()
            assertThat(clientRefs).describedAs(file.name).isSubsetOf("com/fywinds/tc4researchsolver/client/ClientBootstrap")
        }
    }

    @Test
    fun `solver is pure Kotlin`() {
        for (file in classFiles("solver")) {
            assertThat(constants(file)).describedAs(file.name).doesNotContain("net/minecraft/", "thaumcraft/", "cpw/mods/")
        }
    }

    @Test
    fun `every top-level client class is SideOnly CLIENT`() {
        val topLevel = classFiles("client").filter { '$' !in it.name }
        assertThat(topLevel).isNotEmpty
        for (file in topLevel) {
            assertThat(constants(file)).describedAs(file.name).contains("Lcpw/mods/fml/relauncher/SideOnly;")
        }
    }
}
