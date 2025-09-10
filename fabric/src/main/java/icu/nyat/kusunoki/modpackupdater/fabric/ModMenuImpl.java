package icu.nyat.kusunoki.modpackupdater.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

public class ModMenuImpl implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> icu.nyat.kusunoki.modpackupdater.fabric.ui.YaclConfigScreen.create(parent);
    }
}
