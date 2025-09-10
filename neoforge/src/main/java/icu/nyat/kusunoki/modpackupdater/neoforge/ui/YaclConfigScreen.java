package icu.nyat.kusunoki.modpackupdater.neoforge.ui;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import icu.nyat.kusunoki.modpackupdater.Constants;
import icu.nyat.kusunoki.modpackupdater.platform.Services;
import icu.nyat.kusunoki.modpackupdater.updater.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class YaclConfigScreen {
    private YaclConfigScreen() {}

    public static Screen create(Screen parent) {
        Path gameDir = Services.PLATFORM.getGameDirectory();
        Config cfg = Config.load(gameDir);

        // Bindings stored in atomics for lambda setters
        AtomicReference<String> baseUrl = new AtomicReference<>(cfg.getBaseUrl());
        AtomicReference<String> packId = new AtomicReference<>(cfg.getPackId());
        AtomicReference<String> includePaths = new AtomicReference<>(String.join(", ", cfg.getIncludePaths()));
        AtomicInteger timeout = new AtomicInteger((int) cfg.getTimeout().toSeconds());
        AtomicBoolean overwriteModified = new AtomicBoolean(cfg.isOverwriteModifiedConfigs());
        AtomicBoolean overwriteUnmanaged = new AtomicBoolean(cfg.isOverwriteUnmanagedConfigs());
        AtomicBoolean deleteExtra = new AtomicBoolean(cfg.isDeleteExtraConfigs());

        Option<String> baseUrlOpt = Option.<String>createBuilder()
                .name(Component.literal("Base URL"))
                .binding(cfg.getBaseUrl(), baseUrl::get, baseUrl::set)
                .controller(StringControllerBuilder::create)
                .build();

        Option<String> packIdOpt = Option.<String>createBuilder()
                .name(Component.literal("Pack ID"))
                .binding(cfg.getPackId(), packId::get, packId::set)
                .controller(StringControllerBuilder::create)
                .build();

        Option<String> includePathsOpt = Option.<String>createBuilder()
                .name(Component.literal("Include Paths (comma-separated)"))
                .binding(String.join(", ", cfg.getIncludePaths()), includePaths::get, includePaths::set)
                .controller(StringControllerBuilder::create)
                .build();

        Option<Integer> timeoutOpt = Option.<Integer>createBuilder()
                .name(Component.literal("Timeout (seconds)"))
                .binding((int) cfg.getTimeout().toSeconds(), timeout::get, timeout::set)
                .controller(opt -> IntegerFieldControllerBuilder.create(opt).range(5, 600))
                .build();

        Option<Boolean> overwriteModifiedOpt = Option.<Boolean>createBuilder()
                .name(Component.literal("Overwrite modified configs"))
                .binding(cfg.isOverwriteModifiedConfigs(), overwriteModified::get, overwriteModified::set)
                .controller(TickBoxControllerBuilder::create)
                .build();

        Option<Boolean> deleteExtraOpt = Option.<Boolean>createBuilder()
                .name(Component.literal("Delete extra configs"))
                .binding(cfg.isDeleteExtraConfigs(), deleteExtra::get, deleteExtra::set)
                .controller(TickBoxControllerBuilder::create)
                .build();

        Option<Boolean> overwriteUnmanagedOpt = Option.<Boolean>createBuilder()
                .name(Component.literal("Overwrite unmanaged configs"))
                .binding(cfg.isOverwriteUnmanagedConfigs(), overwriteUnmanaged::get, overwriteUnmanaged::set)
                .controller(TickBoxControllerBuilder::create)
                .build();

        ConfigCategory general = ConfigCategory.createBuilder()
                .name(Component.literal("General"))
                .group(OptionGroup.createBuilder()
                        .name(Component.literal("Connection"))
                        .option(baseUrlOpt)
                        .option(packIdOpt)
                        .option(timeoutOpt)
                        .build())
                .build();

        ConfigCategory paths = ConfigCategory.createBuilder()
                .name(Component.literal("Paths"))
                .group(OptionGroup.createBuilder()
                        .name(Component.literal("Include"))
                        .option(includePathsOpt)
                        .build())
                .build();

        ConfigCategory configFolder = ConfigCategory.createBuilder()
                .name(Component.literal("Config folder"))
                .group(OptionGroup.createBuilder()
                        .name(Component.literal("Behavior"))
                        .option(overwriteModifiedOpt)
                        .option(overwriteUnmanagedOpt)
                        .option(deleteExtraOpt)
                        .build())
                .build();

        var yacl = YetAnotherConfigLib.createBuilder()
                .title(Component.literal("ModPackUpdater Config"))
                .category(general)
                .category(paths)
                .category(configFolder)
                .save(() -> {
                    Config current = Config.load(gameDir);
                    String url = baseUrl.get().trim();
                    if (url.endsWith("/")) url = url.substring(0, url.length() - 1);
                    current.setBaseUrl(url);
                    current.setPackId(packId.get().trim());
                    current.setTimeoutSeconds(Math.max(5, timeout.get()));
                    current.setIncludePaths(parseInclude(includePaths.get()));
                    current.setOverwriteModifiedConfigs(overwriteModified.get());
                    current.setOverwriteUnmanagedConfigs(overwriteUnmanaged.get());
                    current.setDeleteExtraConfigs(deleteExtra.get());
                    try { current.saveToDefault(gameDir); } catch (Exception ignored) {}
                })
                .build();

        return yacl.generateScreen(parent);
    }

    private static String[] parseInclude(String s) {
        String[] raw = s.split(",");
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String r : raw) {
            String t = r.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.toArray(new String[0]);
    }

    private static void toast(Component msg) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.displayClientMessage(msg, false);
        });
    }
}
