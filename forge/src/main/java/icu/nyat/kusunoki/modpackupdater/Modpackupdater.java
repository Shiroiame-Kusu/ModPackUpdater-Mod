package icu.nyat.kusunoki.modpackupdater;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(Constants.MOD_ID)
public class Modpackupdater {

    public Modpackupdater() {
        // This method is invoked by the Forge mod loader when it is ready
        // to load your mod. You can access Forge and Common code in this
        // project.

        // Use Forge to bootstrap the Common mod.
        Constants.LOG.info("Hello Forge world!");
        CommonClass.init();

        if (FMLEnvironment.dist == Dist.CLIENT) {
            // Register config screen in Forge Mods UI
            ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new icu.nyat.kusunoki.modpackupdater.ui.ConfigScreen(parent)));
        }
    }
}
