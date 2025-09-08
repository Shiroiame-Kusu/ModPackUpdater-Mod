package icu.nyat.kusunoki.modpackupdater.mixin;

import icu.nyat.kusunoki.modpackupdater.Constants;
import icu.nyat.kusunoki.modpackupdater.platform.Services;
import icu.nyat.kusunoki.modpackupdater.version.VersionGuard;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft {

    @Inject(at = @At("TAIL"), method = "<init>")
    private void init(CallbackInfo info) {
        Constants.LOG.info("This line is printed by the ModPackUpdater common mixin!");
        Constants.LOG.info("MC Version: {}", Minecraft.getInstance().getVersionType());
        // Perform a synchronous version check so that TitleScreen can be blocked if needed
        try {
            VersionGuard.checkNow(Services.PLATFORM.getGameDirectory());
        } catch (Throwable t) {
            Constants.LOG.warn("Version check failed: {}", t.toString());
        }
    }
}
