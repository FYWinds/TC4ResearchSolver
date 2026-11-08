package com.fywinds.tc4researchsolver.client

import com.fywinds.tc4researchsolver.solver.Action
import com.fywinds.tc4researchsolver.solver.Hex
import com.fywinds.tc4researchsolver.solver.PlanInput
import com.fywinds.tc4researchsolver.solver.PlanOutcome
import com.fywinds.tc4researchsolver.solver.ResearchPlanner
import cpw.mods.fml.relauncher.Side
import cpw.mods.fml.relauncher.SideOnly
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.inventory.GuiContainer
import net.minecraft.client.resources.I18n
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.util.ChatComponentTranslation
import net.minecraft.util.EnumChatFormatting
import org.apache.logging.log4j.LogManager
import thaumcraft.api.aspects.Aspect
import thaumcraft.common.lib.network.PacketHandler
import thaumcraft.common.lib.network.playerdata.PacketAspectCombinationToServer
import thaumcraft.common.lib.network.playerdata.PacketAspectPlaceToServer
import thaumcraft.common.tiles.TileResearchTable

/**
 * One "solve the current note" run, driven by client ticks:
 * plan from the synced state, send the packets (capped per tick), wait for the server's sync,
 * and re-plan from the fresh state at most [MAX_REPLANS] times if the note is still incomplete.
 */
@SideOnly(Side.CLIENT)
class SolveSession private constructor(
    private val gui: GuiContainer,
    private val table: TileResearchTable,
    private val noteKey: String,
    private val roots: Map<Hex, String>,
) {
    var finished: Boolean = false
        private set

    private var actions: List<Action> = emptyList()
    private var placements: Map<Hex, String> = emptyMap()
    private var next = 0
    private var waiting = false
    private var waitedTicks = 0
    private var settledTicks = 0
    private var replans = 0
    private var combinationsSent = 0
    private var placementsSent = 0

    fun progressLabel(): String =
        if (waiting) I18n.format("tc4researchsolver.button.syncing") else "$next/${actions.size}"

    fun tick(mc: Minecraft) {
        if (finished) return
        val player = mc.thePlayer
        if (player == null || mc.currentScreen !== gui) return stop("guiClosed")
        val note = TableReader.note(table)
        if (note == null || note.key != noteKey || note.roots != roots) return stop("noteChanged")
        if (note.complete) return stop("done", combinationsSent, placementsSent)

        if (!waiting) {
            val placementsLeft = (next until actions.size).count { actions[it] is Action.Place }
            if (placementsLeft > 0 && TableReader.ink(table) == 0) return stop("inkOut")
            var budget = SolverConfig.packetsPerTick
            while (budget > 0 && next < actions.size) {
                send(player, actions[next])
                next++
                budget--
            }
            if (next >= actions.size) {
                waiting = true
                waitedTicks = 0
                settledTicks = 0
            }
            return
        }

        // Every placement shows up in the synced note before the completion flag does; once all of them
        // are visible and the note stays incomplete, the server's answer is final.
        waitedTicks++
        val allVisible = placements.all { (hex, tag) -> note.fixed[hex] == tag }
        settledTicks = if (allVisible) settledTicks + 1 else 0
        if (settledTicks < SETTLE_TICKS && waitedTicks < SolverConfig.syncTimeoutTicks) return
        if (replans >= MAX_REPLANS) return stop("notComplete", replans + 1)
        replans++
        plan(player, note)
    }

    /** Plans from the current synced state; reports and finishes the session when nothing can be sent. */
    private fun plan(player: EntityPlayer, note: NoteView) {
        val input = PlanInput(
            grid = note.grid,
            fixed = note.fixed,
            roots = note.roots.keys,
            graph = TableReader.aspectGraph(),
            state = TableReader.aspectState(player, table),
            ink = TableReader.ink(table),
            weights = SolverConfig.weights,
            discount = SolverConfig.stockDiscount,
        )
        when (val outcome = ResearchPlanner.plan(input)) {
            is PlanOutcome.Ready -> {
                val unresolved = outcome.actions.firstOrNull { action -> tagsOf(action).any { Aspect.getAspect(it) == null } }
                if (unresolved != null) return stop("uncovered", unresolved.toString())
                actions = outcome.actions
                placements = outcome.placements
                next = 0
                waiting = false
                log.info(
                    "Plan for {}: {} combinations, {} placements, cost {}",
                    noteKey, outcome.combinations, outcome.placements.size, outcome.cost,
                )
            }
            is PlanOutcome.MissingAspects -> stop("missingAspects", formatShortfall(outcome.shortfall))
            is PlanOutcome.MissingInk -> stop("missingInk", outcome.needed, outcome.available)
            PlanOutcome.NoRoute -> stop("noRoute")
            is PlanOutcome.Uncovered -> {
                log.error("Planner produced a packet the simulation cannot cover: {}", outcome.blocked)
                stop("uncovered", outcome.blocked)
            }
        }
    }

    private fun send(player: EntityPlayer, action: Action) {
        when (action) {
            is Action.Combine -> {
                PacketHandler.INSTANCE.sendToServer(
                    PacketAspectCombinationToServer(
                        player, table.xCoord, table.yCoord, table.zCoord,
                        Aspect.getAspect(action.first), Aspect.getAspect(action.second),
                        action.firstFromBonus, action.secondFromBonus, true,
                    ),
                )
                combinationsSent++
            }
            is Action.Place -> {
                PacketHandler.INSTANCE.sendToServer(
                    PacketAspectPlaceToServer(
                        player, action.hex.q.toByte(), action.hex.r.toByte(),
                        table.xCoord, table.yCoord, table.zCoord, Aspect.getAspect(action.tag),
                    ),
                )
                placementsSent++
            }
        }
    }

    private fun stop(messageKey: String, vararg args: Any) {
        finished = true
        chat(messageKey, *args)
    }

    companion object {
        private val log = LogManager.getLogger("TC4ResearchSolver")
        private const val MAX_REPLANS = 2
        private const val SETTLE_TICKS = 10

        /** Starts a run, or reports in chat why none is possible and returns null. */
        fun start(gui: GuiContainer, table: TileResearchTable, player: EntityPlayer): SolveSession? {
            val note = TableReader.note(table)
            if (note == null) {
                chat("noNote")
                return null
            }
            if (note.complete) {
                chat("alreadyComplete")
                return null
            }
            val session = SolveSession(gui, table, note.key, note.roots)
            session.plan(player, note)
            return session.takeUnless { it.finished }
        }

        private fun tagsOf(action: Action): List<String> = when (action) {
            is Action.Combine -> listOf(action.first, action.second)
            is Action.Place -> listOf(action.tag)
        }

        private fun formatShortfall(shortfall: Map<String, Int>): String =
            shortfall.entries.joinToString(", ") { (tag, n) -> "${Aspect.getAspect(tag)?.name ?: tag} ×$n" }

        private fun chat(key: String, vararg args: Any) {
            val player = Minecraft.getMinecraft().thePlayer ?: return
            val color = when (key) {
                "done" -> EnumChatFormatting.GREEN
                "noNote", "alreadyComplete", "guiClosed", "noteChanged" -> EnumChatFormatting.YELLOW
                else -> EnumChatFormatting.RED
            }
            val body = ChatComponentTranslation("tc4researchsolver.msg.$key", *args)
            body.chatStyle.color = color
            val line = ChatComponentTranslation("tc4researchsolver.chat", body)
            line.chatStyle.color = color
            player.addChatMessage(line)
        }
    }
}
