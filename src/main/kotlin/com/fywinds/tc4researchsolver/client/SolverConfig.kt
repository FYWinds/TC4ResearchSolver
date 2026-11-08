package com.fywinds.tc4researchsolver.client

import com.fywinds.tc4researchsolver.solver.AspectWeights
import cpw.mods.fml.relauncher.Side
import cpw.mods.fml.relauncher.SideOnly
import net.minecraftforge.common.config.Configuration
import org.apache.logging.log4j.LogManager
import java.io.File

@SideOnly(Side.CLIENT)
object SolverConfig {
    private const val CATEGORY = "solver"
    private val log = LogManager.getLogger("TC4ResearchSolver")

    var weights: AspectWeights = AspectWeights.parse(AspectWeights.DEFAULT_ENTRIES).first
        private set
    var stockDiscount: Double = 0.5
        private set
    var packetsPerTick: Int = 1
        private set
    var syncTimeoutTicks: Int = 100
        private set

    fun load(file: File) {
        val config = Configuration(file)
        config.load()
        val weightLines = config.getStringList(
            "weights", CATEGORY, AspectWeights.DEFAULT_ENTRIES.toTypedArray(),
            "Aspect weights as tag=weight, lower is cheaper. Tags match case-insensitively; " +
                "aspects not listed weigh ${AspectWeights.DEFAULT_WEIGHT.toInt()}.",
        )
        val (parsed, rejected) = AspectWeights.parse(weightLines.toList())
        if (rejected.isNotEmpty()) log.warn("Ignoring malformed weight entries: {}", rejected)
        weights = parsed
        stockDiscount = config.get(
            CATEGORY, "stockDiscount", 0.5,
            "Cost factor for a placed aspect taken directly from your aspect pool or the table bonus " +
                "(cost = weight x factor). Aspects consumed by combinations cost their full weight.",
            0.0, 1000.0,
        ).getDouble(0.5)
        packetsPerTick = config.getInt(
            "packetsPerTick", CATEGORY, 1, 1, 256,
            "Maximum packets sent to the server per client tick (1 = about 20 packets per second).",
        )
        syncTimeoutTicks = config.getInt(
            "syncTimeoutTicks", CATEGORY, 100, 20, 2400,
            "Ticks to wait for the server's sync before re-planning. Counting starts after the last packet " +
                "of the plan has been sent, so long plans sent at a low packet rate do not time out.",
        )
        if (config.hasChanged()) config.save()
    }
}
