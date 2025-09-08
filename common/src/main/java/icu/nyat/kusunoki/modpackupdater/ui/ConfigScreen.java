package icu.nyat.kusunoki.modpackupdater.ui;

import icu.nyat.kusunoki.modpackupdater.Constants;
import icu.nyat.kusunoki.modpackupdater.platform.Services;
import icu.nyat.kusunoki.modpackupdater.updater.Config;
import icu.nyat.kusunoki.modpackupdater.updater.UpdateRunner;
import icu.nyat.kusunoki.modpackupdater.updater.api.ApiClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ConfigScreen extends Screen {
    private final Screen parent;

    // Widgets
    private EditBox baseUrlBox;
    private EditBox packIdBox;
    private EditBox includePathsBox;
    private EditBox timeoutBox;
    private Checkbox autoUpdateBox;

    // New config-folder behavior checkboxes
    private Checkbox overwriteConfigsBox;
    private Checkbox deleteExtraConfigsBox;
    private Checkbox overwriteUnmanagedConfigsBox;

    private Button testButton;
    private Button updateNowButton;

    // Data
    private Config config;

    // UI state
    private String statusMessage = "";
    private int statusColor = 0xCCCCCC;

    // Layout
    private int panelLeft;
    private int panelTop;
    private int panelWidth;

    // Scroll state
    private static class WidgetEntry {
        final AbstractWidget widget;
        final int baseY;
        WidgetEntry(AbstractWidget w, int baseY) { this.widget = w; this.baseY = baseY; }
    }
    private final List<WidgetEntry> scrolled = new ArrayList<>();
    private int contentHeight;   // total content height (from panelTop)
    private int viewportHeight;  // visible area height
    private int scrollOffset;    // current scroll position

    public ConfigScreen(Screen parent) {
        super(Component.literal("ModPackUpdater Config"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Path gameDir = Services.PLATFORM.getGameDirectory();
        this.config = Config.load(gameDir);

        // Panel metrics
        panelWidth = 360;
        panelLeft = (this.width - panelWidth) / 2;
        panelTop = this.height / 6;
        int fieldLeft = panelLeft + 12;
        int y = panelTop + 28; // first row
        int gap = 36;          // vertical distance between rows

        scrolled.clear();
        scrollOffset = 0;

        // Base URL
        baseUrlBox = new EditBox(this.font, fieldLeft, y, panelWidth - 24, 20, Component.literal("Base URL"));
        baseUrlBox.setValue(config.getBaseUrl());
        this.addRenderableWidget(baseUrlBox);
        scrolled.add(new WidgetEntry(baseUrlBox, y));
        y += gap;

        // Pack ID
        packIdBox = new EditBox(this.font, fieldLeft, y, panelWidth - 24, 20, Component.literal("Pack ID"));
        packIdBox.setValue(config.getPackId());
        this.addRenderableWidget(packIdBox);
        scrolled.add(new WidgetEntry(packIdBox, y));
        y += gap;

        // Include Paths
        includePathsBox = new EditBox(this.font, fieldLeft, y, panelWidth - 24, 20, Component.literal("Include Paths (comma-separated)"));
        includePathsBox.setMaxLength(4095);
        includePathsBox.setValue(String.join(", ", config.getIncludePaths()));
        this.addRenderableWidget(includePathsBox);
        scrolled.add(new WidgetEntry(includePathsBox, y));
        y += gap;

        // Timeout + Auto update (same row)
        timeoutBox = new EditBox(this.font, fieldLeft, y, 140, 20, Component.literal("Timeout Seconds"));
        timeoutBox.setValue(String.valueOf(config.getTimeout().toSeconds()));
        this.addRenderableWidget(timeoutBox);
        scrolled.add(new WidgetEntry(timeoutBox, y));

        autoUpdateBox = Checkbox.builder(Component.literal("Enable auto update"), this.font)
                .pos(fieldLeft + 150, y - 1) // align baseline
                .selected(config.isAutoUpdate())
                .build();
        this.addRenderableWidget(autoUpdateBox);
        scrolled.add(new WidgetEntry(autoUpdateBox, y - 1));
        y += gap + 4;

        // Config folder behavior (only meaningful if 'config' is in includePaths)
        boolean configIncluded = hasIncludeFolder(config.getIncludePaths(), "config");
        overwriteConfigsBox = Checkbox.builder(Component.literal("Overwrite modified configs"), this.font)
                .pos(fieldLeft, y - 1)
                .selected(config.isOverwriteModifiedConfigs())
                .build();
        overwriteConfigsBox.active = configIncluded;
        this.addRenderableWidget(overwriteConfigsBox);
        scrolled.add(new WidgetEntry(overwriteConfigsBox, y - 1));

        deleteExtraConfigsBox = Checkbox.builder(Component.literal("Delete extra configs"), this.font)
                .pos(fieldLeft + 180, y - 1)
                .selected(config.isDeleteExtraConfigs())
                .build();
        deleteExtraConfigsBox.active = configIncluded;
        this.addRenderableWidget(deleteExtraConfigsBox);
        scrolled.add(new WidgetEntry(deleteExtraConfigsBox, y - 1));
        y += gap;

        // Buttons row 1
        int btnY = y;
        Button saveBtn = Button.builder(Component.literal("Save"), b -> saveAndClose())
                .bounds(fieldLeft, btnY, 90, 20)
                .build();
        this.addRenderableWidget(saveBtn);
        scrolled.add(new WidgetEntry(saveBtn, btnY));

        Button cancelBtn = Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(fieldLeft + 96, btnY, 90, 20)
                .build();
        this.addRenderableWidget(cancelBtn);
        scrolled.add(new WidgetEntry(cancelBtn, btnY));

        testButton = Button.builder(Component.literal("Test Server"), b -> testServer())
                .bounds(fieldLeft + 192, btnY, 140, 20)
                .build();
        this.addRenderableWidget(testButton);
        scrolled.add(new WidgetEntry(testButton, btnY));

        // Buttons row 2
        btnY += gap;
        Button resetBtn = Button.builder(Component.literal("Reset Defaults"), b -> resetDefaults())
                .bounds(fieldLeft, btnY, 140, 20)
                .build();
        this.addRenderableWidget(resetBtn);
        scrolled.add(new WidgetEntry(resetBtn, btnY));

        updateNowButton = Button.builder(Component.literal("Run Update Now"), b -> runUpdateNow())
                .bounds(fieldLeft + 150, btnY, 182, 20)
                .build();
        this.addRenderableWidget(updateNowButton);
        scrolled.add(new WidgetEntry(updateNowButton, btnY));

        // Compute content & viewport heights
        int lastBaseY = btnY;
        contentHeight = (lastBaseY + 20) - panelTop + 20; // bottom of last row + padding
        viewportHeight = Math.min(contentHeight, 240);    // visible area height
        clampScroll();
        applyScrollPositions();
    }

    private void clampScroll() {
        int max = Math.max(0, contentHeight - viewportHeight);
        if (scrollOffset < 0) scrollOffset = 0;
        if (scrollOffset > max) scrollOffset = max;
    }

    private void applyScrollPositions() {
        int viewTop = panelTop;
        int viewBottom = panelTop + viewportHeight;
        for (WidgetEntry e : scrolled) {
            int newY = e.baseY - scrollOffset;
            e.widget.setY(newY);
            boolean visible = newY + e.widget.getHeight() > viewTop && newY < viewBottom;
            e.widget.visible = visible;
            e.widget.active = visible;
        }
    }

    private void resetDefaults() {
        this.config = new Config();
        baseUrlBox.setValue(config.getBaseUrl());
        packIdBox.setValue(config.getPackId());
        includePathsBox.setValue(String.join(", ", config.getIncludePaths()));
        timeoutBox.setValue(String.valueOf(config.getTimeout().toSeconds()));
        boolean desired = config.isAutoUpdate();
        if (autoUpdateBox.selected() != desired) autoUpdateBox.onPress();
        // Reset config-folder options
        boolean cfgIncluded = hasIncludeFolder(config.getIncludePaths(), "config");
        overwriteConfigsBox.active = cfgIncluded;
        deleteExtraConfigsBox.active = cfgIncluded;
        overwriteUnmanagedConfigsBox.active = cfgIncluded;
        if (overwriteConfigsBox.selected() != config.isOverwriteModifiedConfigs()) overwriteConfigsBox.onPress();
        if (deleteExtraConfigsBox.selected() != config.isDeleteExtraConfigs()) deleteExtraConfigsBox.onPress();
        if (overwriteUnmanagedConfigsBox.selected() != config.isOverwriteUnmanagedConfigs()) overwriteUnmanagedConfigsBox.onPress();
        setStatus("Defaults restored (not saved yet)", 0xFFFFAA);
    }

    private void testServer() {
        testButton.active = false;
        setStatus("Testing server...", 0xCCCCCC);
        new Thread(() -> {
            try {
                Config tmp = snapshotConfigFromUI();
                ApiClient api = new ApiClient(tmp);
                var manifest = api.getManifest();
                int count = (manifest != null && manifest.files != null) ? manifest.files.size() : 0;
                setStatus("Server OK. Files in manifest: " + count, 0x55FF55);
            } catch (Exception e) {
                setStatus("Test failed: " + e.getMessage(), 0xFF5555);
            } finally {
                testButton.active = true;
            }
        }, "MPU-TestServer").start();
    }

    private void runUpdateNow() {
        updateNowButton.active = false;
        setStatus("Updating...", 0xCCCCCC);
        Path gameDir = Services.PLATFORM.getGameDirectory();
        Config snapshot = snapshotConfigFromUI();
        new Thread(() -> {
            try {
                new UpdateRunner(gameDir, snapshot).run();
                setStatus("Update finished. See logs for details.", 0x55FF55);
            } catch (Throwable t) {
                setStatus("Update failed: " + t.getMessage(), 0xFF5555);
            } finally {
                updateNowButton.active = true;
            }
        }, "MPU-ManualUpdate").start();
    }

    private Config snapshotConfigFromUI() {
        Config c = new Config();
        String baseUrl = baseUrlBox.getValue().trim();
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        c.setBaseUrl(baseUrl);
        c.setPackId(packIdBox.getValue().trim());
        int timeout = 30;
        try { timeout = Math.max(5, Integer.parseInt(timeoutBox.getValue().trim())); } catch (NumberFormatException ignored) {}
        c.setTimeoutSeconds(timeout);
        c.setAutoUpdate(autoUpdateBox.selected());
        c.setIncludePaths(parseIncludePaths(includePathsBox.getValue()));
        // config-folder options
        c.setOverwriteModifiedConfigs(overwriteConfigsBox != null && overwriteConfigsBox.selected());
        c.setDeleteExtraConfigs(deleteExtraConfigsBox != null && deleteExtraConfigsBox.selected());
        c.setOverwriteUnmanagedConfigs(overwriteUnmanagedConfigsBox != null && overwriteUnmanagedConfigsBox.selected());
        return c;
    }

    private static String[] parseIncludePaths(String text) {
        String[] raw = text.split(",");
        List<String> out = new ArrayList<>();
        for (String s : raw) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.toArray(new String[0]);
    }

    private static boolean hasIncludeFolder(String[] includePaths, String folderName) {
        if (includePaths == null) return false;
        for (String inc : includePaths) {
            if (inc == null || inc.isBlank()) continue;
            if (inc.replace('\\', '/').equalsIgnoreCase(folderName)) return true;
        }
        return false;
    }

    private void setStatus(String msg, int color) {
        this.statusMessage = msg;
        this.statusColor = color;
    }

    private void setScrollOffset(int newOffset) {
        this.scrollOffset = newOffset;
        clampScroll();
        applyScrollPositions();
    }

    // 1.21+ signature (safe overload if not present in target)
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (isInsideViewport(mouseX, mouseY) && contentHeight > viewportHeight) {
            int dir = (int) Math.signum(scrollY != 0 ? scrollY : scrollX);
            setScrollOffset(this.scrollOffset - dir * 24);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private boolean isInsideViewport(double mouseX, double mouseY) {
        int viewTop = panelTop;
        int viewBottom = panelTop + viewportHeight;
        int right = panelLeft + panelWidth;
        return mouseX >= panelLeft && mouseX <= right && mouseY >= viewTop && mouseY <= viewBottom;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        // Recompute viewport height in case of window changes
        viewportHeight = Math.min(contentHeight, 240);
        clampScroll();
        applyScrollPositions();

        this.renderBackground(gfx, mouseX, mouseY, delta);

        int right = panelLeft + panelWidth;
        int bottom = panelTop + viewportHeight + 40; // panel + status area

        // Panel background
        gfx.fill(panelLeft - 2, panelTop - 2, right + 2, bottom, 0x88000000);
        gfx.fill(panelLeft, panelTop, right, bottom, 0x66000000);

        super.render(gfx, mouseX, mouseY, delta);

        // Title
        gfx.drawCenteredString(this.font, this.title, this.width / 2, panelTop - 14, 0xFFFFFF);

        // Labels drawn using actual widget Y positions to avoid overlap (only if visible)
        int labelColor = 0xAAAAAA;
        if (baseUrlBox.visible) drawLabelAbove(gfx, baseUrlBox, "Base URL", labelColor);
        if (packIdBox.visible) drawLabelAbove(gfx, packIdBox, "Pack ID", labelColor);
        if (includePathsBox.visible) drawLabelAbove(gfx, includePathsBox, "Include Paths (comma-separated)", labelColor);
        if (timeoutBox.visible) drawLabelAbove(gfx, timeoutBox, "Timeout (seconds) & Auto Update", labelColor);
        if (overwriteConfigsBox != null && deleteExtraConfigsBox != null && (overwriteConfigsBox.visible || deleteExtraConfigsBox.visible)) {
            int y = overwriteConfigsBox.getY() - 12;
            gfx.drawString(this.font, "Config folder options", overwriteConfigsBox.getX(), y, labelColor, false);
        }

        // Draw scrollbar thumb if needed
        if (contentHeight > viewportHeight) {
            int barX = right - 6;
            int barTop = panelTop + 2;
            int barBottom = panelTop + viewportHeight - 2;
            int barHeight = barBottom - barTop;
            int thumbHeight = Math.max(20, (int) ((long) viewportHeight * viewportHeight / (long) contentHeight));
            int maxThumbTravel = barHeight - thumbHeight;
            int thumbY = barTop + (maxThumbTravel * scrollOffset) / Math.max(1, contentHeight - viewportHeight);
            int thumbColor = 0xAAFFFFFF;
            int trackColor = 0x33000000;
            gfx.fill(barX, barTop, barX + 4, barBottom, trackColor);
            gfx.fill(barX, thumbY, barX + 4, thumbY + thumbHeight, thumbColor);
        }

        // Status line
        if (!statusMessage.isEmpty()) {
            gfx.drawCenteredString(this.font, Component.literal(statusMessage), this.width / 2, bottom - 18, statusColor);
        }
    }

    private void drawLabelAbove(GuiGraphics gfx, EditBox field, String text, int color) {
        if (field == null) return;
        int x = field.getX();
        int y = field.getY() - 12; // above the field
        gfx.drawString(this.font, text, x, y, color, false);
    }

    private void saveAndClose() {
        try {
            Config snapshot = snapshotConfigFromUI();
            snapshot.save(Config.configFile(Services.PLATFORM.getGameDirectory()));
            setStatus("Saved", 0x55FF55);
        } catch (IOException e) {
            Constants.LOG.error("Failed to save ModPackUpdater config: {}", e.toString());
            setStatus("Save failed: " + e.getMessage(), 0xFF5555);
        }
        onClose();
    }
}
