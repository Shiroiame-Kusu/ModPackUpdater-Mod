package icu.nyat.kusunoki.modpackupdater;


import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(Constants.MOD_ID)
public class Modpackupdater {

    public Modpackupdater(IEventBus eventBus) {
        // This method is invoked by the NeoForge mod loader when it is ready
        // to load your mod. You can access NeoForge and Common code in this
        // project.

        // Use NeoForge to bootstrap the Common mod.
        //Constants.LOG.info("Hello NeoForge world!");
        CommonClass.init();

        if (FMLEnvironment.dist == Dist.CLIENT) {
            // Register config screen for NeoForge Mods UI (current API expects (Minecraft, Screen))
            ModLoadingContext.get().registerExtensionPoint(IConfigScreenFactory.class,
                    () -> (mc, parent) -> new icu.nyat.kusunoki.modpackupdater.ui.ConfigScreen(parent));
        }
    }
}
