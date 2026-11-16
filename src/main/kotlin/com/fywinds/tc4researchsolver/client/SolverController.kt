package com.fywinds.tc4researchsolver.client

import com.fywinds.tc4researchsolver.solver.Hex
import cpw.mods.fml.common.FMLCommonHandler
import cpw.mods.fml.common.eventhandler.SubscribeEvent
import cpw.mods.fml.common.gameevent.TickEvent
import cpw.mods.fml.relauncher.ReflectionHelper
import cpw.mods.fml.relauncher.Side
import cpw.mods.fml.relauncher.SideOnly
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiButton
import net.minecraft.client.gui.inventory.GuiContainer
import net.minecraft.client.resources.I18n
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.item.ItemStack
import net.minecraftforge.client.event.GuiScreenEvent
import net.minecraftforge.common.MinecraftForge
import thaumcraft.common.tiles.TileResearchTable
import java.io.File
import java.lang.reflect.Field

/** Client entry point; only ever called when FML reports the client side. */
@SideOnly(Side.CLIENT)
object ClientBootstrap {
    fun init(configFile: File) {
        SolverConfig.load(configFile)
        MinecraftForge.EVENT_BUS.register(SolverController)
        FMLCommonHandler.instance().bus().register(SolverController)
    }
}

/**
 * The research-table button and the tick driver. Registered on the Forge bus (GUI events) and the FML bus
 * (client ticks). Works for any [GuiContainer] whose container has a slot backed by a `TileResearchTable`:
 * vanilla `GuiResearchTable` and TRT's `ComposableContainerGui` both call `super.drawScreen` and
 * `super.mouseClicked`, so a Forge button renders and fires in both.
 *
 * With [SolverConfig.autoSolve] on, a tick without a run that finds the table screen showing an unsolved note starts
 * one as the button would. Every note a run was started for, by the button or on its own, is remembered and never
 * started on its own again, so a run that failed or was stopped does not repeat until another note is in the table.
 */
@SideOnly(Side.CLIENT)
object SolverController {
    private const val BUTTON_ID = 0x7C4A5
    private const val BUTTON_WIDTH = 60
    private const val BUTTON_HEIGHT = 20
    private const val GAP = 4

    // GuiContainer.guiLeft/guiTop are protected; read them instead of re-deriving from the protected xSize/ySize.
    private val guiLeft: Field = ReflectionHelper.findField(GuiContainer::class.java, "guiLeft", "field_147003_i")
    private val guiTop: Field = ReflectionHelper.findField(GuiContainer::class.java, "guiTop", "field_147009_r")

    private var button: GuiButton? = null
    private var session: SolveSession? = null

    /** The table screen the button was last added to, with its table; dropped once another screen shows. */
    private var screen: GuiContainer? = null
    private var table: TileResearchTable? = null

    /** The note stack [autoStart] last looked at; the slot is only read again once it holds another stack. */
    private var lastSeen: ItemStack? = null

    /** The notes a run was started for. */
    private val started = HashSet<NoteId>()

    /** A note as far as starting on its own goes: its research and where its root aspects sit. */
    private data class NoteId(val key: String, val roots: Map<Hex, String>) {
        constructor(note: NoteView) : this(note.key, note.roots)
    }

    @SubscribeEvent
    fun onInitGui(event: GuiScreenEvent.InitGuiEvent.Post) {
        val gui = event.gui as? GuiContainer ?: return
        val found = TableReader.tableOf(gui) ?: return
        val x = maxOf(GAP, guiLeft.getInt(gui) - BUTTON_WIDTH - GAP)
        val y = guiTop.getInt(gui) + GAP
        val added = GuiButton(BUTTON_ID, x, y, BUTTON_WIDTH, BUTTON_HEIGHT, "")
        event.buttonList.add(added)
        button = added
        screen = gui
        table = found
        refreshButton()
    }

    @SubscribeEvent
    fun onButton(event: GuiScreenEvent.ActionPerformedEvent.Pre) {
        if (event.button !== button || session != null) return
        val gui = event.gui as? GuiContainer ?: return
        val table = TableReader.tableOf(gui) ?: return
        val player = Minecraft.getMinecraft().thePlayer ?: return
        start(gui, table, player)
    }

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val mc = Minecraft.getMinecraft()
        if (screen != null && mc.currentScreen !== screen) {
            screen = null
            table = null
            lastSeen = null
        }
        val running = session
        if (running == null) {
            if (SolverConfig.autoSolve) autoStart(mc)
            return
        }
        running.tick(mc)
        if (running.finished) session = null
        refreshButton()
    }

    /** Starts a run for an unsolved note in the open table screen that no run was started for yet. */
    private fun autoStart(mc: Minecraft) {
        val gui = screen ?: return
        val table = table ?: return
        val player = mc.thePlayer ?: return
        val stack = table.getStackInSlot(TableReader.NOTE_SLOT)
        if (stack === lastSeen) return
        lastSeen = stack
        val note = TableReader.note(table) ?: return
        if (note.complete || NoteId(note) in started) return
        start(gui, table, player)
    }

    private fun start(gui: GuiContainer, table: TileResearchTable, player: EntityPlayer) {
        TableReader.note(table)?.let { started += NoteId(it) }
        session = SolveSession.start(gui, table, player)
        refreshButton()
    }

    private fun refreshButton() {
        val current = button ?: return
        val running = session
        current.enabled = running == null
        current.displayString = running?.progressLabel() ?: I18n.format("tc4researchsolver.button.solve")
    }
}
