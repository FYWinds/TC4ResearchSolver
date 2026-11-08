package com.fywinds.tc4researchsolver.client

import com.fywinds.tc4researchsolver.solver.AspectGraph
import com.fywinds.tc4researchsolver.solver.AspectState
import com.fywinds.tc4researchsolver.solver.Hex
import cpw.mods.fml.relauncher.Side
import cpw.mods.fml.relauncher.SideOnly
import net.minecraft.client.gui.inventory.GuiContainer
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.inventory.Slot
import net.minecraft.item.ItemStack
import thaumcraft.api.IScribeTools
import thaumcraft.api.aspects.Aspect
import thaumcraft.common.Thaumcraft
import thaumcraft.common.items.ItemResearchNotes
import thaumcraft.common.lib.research.ResearchManager
import thaumcraft.common.tiles.TileResearchTable

/** The research note in the table, as last synced to the client. */
@SideOnly(Side.CLIENT)
class NoteView(
    val key: String,
    val complete: Boolean,
    val grid: Set<Hex>,
    /** Occupied hexes (roots and placed nodes); unregistered aspects map to [UNKNOWN_TAG]. */
    val fixed: Map<Hex, String>,
    val roots: Map<Hex, String>,
) {
    companion object {
        const val UNKNOWN_TAG = "?"
    }
}

/** Reads Thaumcraft state on the client thread. */
@SideOnly(Side.CLIENT)
object TableReader {
    const val TOOLS_SLOT = 0
    const val NOTE_SLOT = 1

    /** `ItemResearchNotes` damage of a completed note (`TileResearchTable.placeAspect`). */
    private const val COMPLETE_DAMAGE = 64

    /** The research table behind a container GUI (vanilla `GuiResearchTable` or TRT's GUI), if any. */
    fun tableOf(gui: GuiContainer): TileResearchTable? {
        for (slot in gui.inventorySlots.inventorySlots) {
            val inventory = (slot as? Slot)?.inventory
            if (inventory is TileResearchTable) return inventory
        }
        return null
    }

    fun note(table: TileResearchTable): NoteView? {
        val stack: ItemStack = table.getStackInSlot(NOTE_SLOT) ?: return null
        if (stack.item !is ItemResearchNotes) return null
        val data = ResearchManager.getData(stack) ?: return null
        val grid = LinkedHashSet<Hex>()
        val fixed = LinkedHashMap<Hex, String>()
        val roots = LinkedHashMap<Hex, String>()
        for ((key, hex) in data.hexes) {
            val h = Hex(hex.q, hex.r)
            grid.add(h)
            val entry = data.hexEntries[key] ?: continue
            if (entry.type >= 1) {
                val tag = entry.aspect?.tag ?: NoteView.UNKNOWN_TAG
                fixed[h] = tag
                if (entry.type == 1) roots[h] = tag
            }
        }
        val complete = data.complete || stack.itemDamage >= COMPLETE_DAMAGE
        return NoteView(data.key ?: "", complete, grid, fixed, roots)
    }

    /** Placements the scribing tools can still pay for: `ResearchManager.consumeInkFromTable`. */
    fun ink(table: TileResearchTable): Int {
        val tools: ItemStack = table.getStackInSlot(TOOLS_SLOT) ?: return 0
        if (tools.item !is IScribeTools) return 0
        return maxOf(0, tools.maxDamage - tools.itemDamage)
    }

    fun aspectState(player: EntityPlayer, table: TileResearchTable): AspectState {
        val known = Thaumcraft.proxy.getPlayerKnowledge().getAspectsDiscovered(player.commandSenderName)
        val pool = HashMap<String, Int>()
        val discovered = HashSet<String>()
        for ((aspect, amount) in known.aspects) {
            if (aspect == null) continue
            discovered.add(aspect.tag)
            pool[aspect.tag] = amount ?: 0
        }
        val bonus = HashMap<String, Int>()
        for ((aspect, amount) in table.bonusAspects.aspects) {
            if (aspect != null && amount != null && amount > 0) bonus[aspect.tag] = amount
        }
        return AspectState(pool, bonus, discovered)
    }

    /** `Aspect.aspects` as a graph; components follow `Aspect.isPrimal()` (anything but two is primal). */
    fun aspectGraph(): AspectGraph {
        val components = LinkedHashMap<String, List<String>>()
        for (aspect in Aspect.aspects.values) {
            val parts = aspect.components
            components[aspect.tag] =
                if (parts == null || parts.size != 2) emptyList() else parts.mapNotNull { it?.tag }
        }
        return AspectGraph(components)
    }
}
