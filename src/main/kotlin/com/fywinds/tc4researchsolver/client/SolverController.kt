package com.fywinds.tc4researchsolver.client

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
import net.minecraftforge.client.event.GuiScreenEvent
import net.minecraftforge.common.MinecraftForge
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

    @SubscribeEvent
    fun onInitGui(event: GuiScreenEvent.InitGuiEvent.Post) {
        val gui = event.gui as? GuiContainer ?: return
        if (TableReader.tableOf(gui) == null) return
        val x = maxOf(GAP, guiLeft.getInt(gui) - BUTTON_WIDTH - GAP)
        val y = guiTop.getInt(gui) + GAP
        val added = GuiButton(BUTTON_ID, x, y, BUTTON_WIDTH, BUTTON_HEIGHT, "")
        event.buttonList.add(added)
        button = added
        refreshButton()
    }

    @SubscribeEvent
    fun onButton(event: GuiScreenEvent.ActionPerformedEvent.Pre) {
        if (event.button !== button || session != null) return
        val gui = event.gui as? GuiContainer ?: return
        val table = TableReader.tableOf(gui) ?: return
        val player = Minecraft.getMinecraft().thePlayer ?: return
        session = SolveSession.start(gui, table, player)
        refreshButton()
    }

    @SubscribeEvent
    fun onClientTick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        val running = session ?: return
        running.tick(Minecraft.getMinecraft())
        if (running.finished) session = null
        refreshButton()
    }

    private fun refreshButton() {
        val current = button ?: return
        val running = session
        current.enabled = running == null
        current.displayString = running?.progressLabel() ?: I18n.format("tc4researchsolver.button.solve")
    }
}
