package com.fywinds.tc4researchsolver

import com.fywinds.tc4researchsolver.client.ClientBootstrap
import cpw.mods.fml.common.Mod
import cpw.mods.fml.common.event.FMLPreInitializationEvent

/**
 * Client-only mod. On a dedicated server it loads and does nothing: the only reference to client code is
 * the [ClientBootstrap] call below, which runs (and therefore loads that class) only on the client side.
 * Every class under `client` is `@SideOnly(Side.CLIENT)`, so an accidental server-side load fails loudly.
 */
@Mod(
    modid = TC4ResearchSolver.MOD_ID,
    name = "TC4 Research Solver",
    version = MODVER,
    acceptableRemoteVersions = "*",
    modLanguageAdapter = "net.shadowfacts.forgelin.KotlinAdapter",
    dependencies = "required-after:Thaumcraft;required-after:forgelin;after:ThaumcraftResearchTweaks",
)
object TC4ResearchSolver {
    const val MOD_ID = "tc4researchsolver"

    @Mod.EventHandler
    fun preInit(event: FMLPreInitializationEvent) {
        if (event.side.isClient) ClientBootstrap.init(event.suggestedConfigurationFile)
    }
}
