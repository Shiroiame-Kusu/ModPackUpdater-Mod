package icu.nyat.kusunoki.modpackupdater.mixin;

import icu.nyat.kusunoki.modpackupdater.Constants;
import icu.nyat.kusunoki.modpackupdater.ui.VersionErrorScreen;
import icu.nyat.kusunoki.modpackupdater.version.VersionGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public class MixinTitleScreen {

    @Inject(at = @At("HEAD"), method = "init()V", cancellable = true)
    private void init(CallbackInfo info) {
        if (VersionGuard.hasMismatch()) {
            Constants.LOG.error("Blocking startup due to environment mismatch");
            Minecraft.getInstance().setScreen(new VersionErrorScreen());
            info.cancel();
            return;
        }
        Constants.LOG.info("This line is printed by the ModPackUpdater mixin from Fabric!");
        Constants.LOG.info("MC Version: {}", Minecraft.getInstance().getVersionType());
    }
}
