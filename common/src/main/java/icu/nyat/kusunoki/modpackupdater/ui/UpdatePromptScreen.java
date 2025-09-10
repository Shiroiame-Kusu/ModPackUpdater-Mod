package icu.nyat.kusunoki.modpackupdater.ui;

import icu.nyat.kusunoki.modpackupdater.updater.Config;
import icu.nyat.kusunoki.modpackupdater.updater.UpdateRunner;
import icu.nyat.kusunoki.modpackupdater.updater.UpdateProgressListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class UpdatePromptScreen extends Screen {
    private final Path gameDirectory;
    private final Config config;

    private final List<Component> addItems;
    private final List<Component> updateItems;
    private final List<Component> deleteItems;

    private Button confirmButton;
    private Button cancelButton;

    // Scrolling state
    private int innerL, innerT, innerR, innerB;
    private int scrollY = 0;
    private int contentHeight = 0;
    private boolean draggingScrollbar = false;
    private int dragStartMouseY;
    private int dragStartScrollY;

    private volatile String statusLine = ""; // current update status text

    public UpdatePromptScreen(Path gameDir, Config cfg, List<String> adds, List<String> updates, List<String> deletes) {
        super(Component.literal("Modpack Update Available"));
        this.gameDirectory = Objects.requireNonNull(gameDir);
        this.config = Objects.requireNonNull(cfg);
        this.addItems = toComponents(adds);
        this.updateItems = toComponents(updates);
        this.deleteItems = toComponents(deletes);
    }

    private static List<Component> toComponents(List<String> src) {
        List<Component> list = new ArrayList<>();
        if (src != null) {
            for (String s : src) list.add(Component.literal(s));
        }
        return list;
    }

    @Override
    protected void init() {
        int btnW = 150;
        int btnH = 20;
        int gap = 10;
        int cx = this.width / 2;
        int y = this.height - 28;

        this.confirmButton = Button.builder(Component.literal("Update now"), b -> startUpdate())
                .bounds(cx - btnW - gap / 2, y, btnW, btnH)
                .build();
        this.addRenderableWidget(this.confirmButton);

        this.cancelButton = Button.builder(Component.literal("Later"), b -> onClose())
                .bounds(cx + gap / 2, y, btnW, btnH)
                .build();
        this.addRenderableWidget(this.cancelButton);
    }

    private void startUpdate() {
        setButtonsEnabled(false);
        statusLine = "Starting update...";
        Thread t = new Thread(() -> {
            boolean success;
            try {
                success = new UpdateRunner(gameDirectory, config, (UpdateProgressListener) msg -> {
                    // Ensure changes applied on render thread
                    Minecraft mc = Minecraft.getInstance();
                    if (mc != null) mc.execute(() -> statusLine = msg);
                }).execute();
            } catch (Throwable t1) {
                success = false;
            }
            final boolean ok = success;
            Minecraft.getInstance().execute(() -> {
                if (ok) {
                    statusLine = "Update complete. Restarting...";
                    try {
                        Minecraft.getInstance().stop();
                    } catch (Throwable ignored) {
                        onClose();
                    }
                } else {
                    statusLine = "Update failed.";
                    SystemToast.add(Minecraft.getInstance().getToasts(), SystemToast.SystemToastId.PACK_LOAD_FAILURE,
                            Component.literal("Update failed"),
                            Component.literal("Starting without updating."));
                    onClose();
                }
            });
        }, "MPU-UpdateRunner");
        t.setDaemon(true);
        t.start();
    }

    private void setButtonsEnabled(boolean enabled) {
        if (confirmButton != null) confirmButton.active = enabled;
        if (cancelButton != null) cancelButton.active = enabled;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public boolean shouldCloseOnEsc() { return true; }

    @Override
    public boolean isPauseScreen() {
        // Prevents vanilla from blurring the background
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        // Solid black background (no blur)
        gfx.fill(0, 0, this.width, this.height, 0x00000000);
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        // Step 1: background
        this.renderBackground(gfx, mouseX, mouseY, delta);

        // Step 2: panel
        int maxPanelW = Math.min(560, this.width - 40);
        int panelH = Math.min(this.height - 80, 260);
        int cx = this.width / 2;
        int panelL = cx - maxPanelW / 2;
        int panelR = panelL + maxPanelW;
        int panelT = 28;
        int panelB = panelT + panelH;

        int pad = 10;
        this.innerL = panelL + pad;
        this.innerR = panelR - pad;
        // reserve 16px at bottom inside panel for status line
        this.innerT = panelT + 28; // leave room for title
        int statusReserve = 16;
        this.innerB = panelB - pad - statusReserve;

        // Panel background and outline
        gfx.fill(panelL, panelT, panelR, panelB, 0xF0000000);
        gfx.renderOutline(panelL, panelT, panelR - panelL, panelB - panelT, 0x55FFFFFF);

        // Title
        gfx.drawCenteredString(this.font, this.title, cx, panelT + 8, 0xFFFFFF);

        // Step 3: scrollable content
        int availableTextW = innerR - innerL - 8;
        contentHeight = measureContentHeight(availableTextW);

        int viewport = Math.max(0, innerB - innerT);
        int maxScroll = Math.max(0, contentHeight - viewport);
        if (scrollY > maxScroll) scrollY = maxScroll;
        if (scrollY < 0) scrollY = 0;

        gfx.enableScissor(innerL, innerT, innerR, innerB);
        int y = innerT - scrollY;
        y = drawSection(gfx, innerL, y, Component.literal("Add"), addItems, availableTextW);
        y = drawSection(gfx, innerL, y, Component.literal("Update"), updateItems, availableTextW);
        drawSection(gfx, innerL, y, Component.literal("Delete"), deleteItems, availableTextW);
        gfx.disableScissor();

        if (maxScroll > 0) drawScrollbar(gfx, maxScroll);

        // Step 4: widgets
        super.render(gfx, mouseX, mouseY, delta);

        // Draw status line (inside panel bottom)
        if (statusLine != null && !statusLine.isEmpty()) {
            String txt = statusLine;
            // Trim if too wide
            int maxW = innerR - innerL;
            if (this.font.width(txt) > maxW) {
                while (txt.length() > 4 && this.font.width(txt + "...") > maxW) {
                    txt = txt.substring(0, txt.length() - 1);
                }
                txt += "...";
            }
            int statusY = panelB - pad - 10; // within reserved space
            gfx.drawString(this.font, txt, innerL, statusY, 0xFFFFFF, false);
        }
    }

    private int measureContentHeight(int wrapWidth) {
        int h = 0;
        h += measureSection(addItems, wrapWidth);
        h += measureSection(updateItems, wrapWidth);
        h += measureSection(deleteItems, wrapWidth);
        return h;
    }

    private int measureSection(List<Component> items, int wrapWidth) {
        int height = 0;
        height += 12; // header
        height += 4;
        for (Component c : items) {
            List<net.minecraft.util.FormattedCharSequence> lines = this.font.split(c, wrapWidth);
            height += lines.size() * 10;
            height += 2;
        }
        height += 8;
        return height;
    }

    private int drawSection(GuiGraphics gfx, int x, int y, Component header, List<Component> items, int wrapWidth) {
        int headerColor = 0xFFFFAA;
        gfx.drawString(this.font, Component.literal(header.getString() + " (" + items.size() + ")"), x, y, headerColor, false);
        y += 12;
        y += 4;

        int bulletIndent = 8;
        for (Component c : items) {
            List<net.minecraft.util.FormattedCharSequence> lines = this.font.split(c, wrapWidth);
            int lineY = y;
            for (net.minecraft.util.FormattedCharSequence seq : lines) {
                if (lineY == y) gfx.drawString(this.font, "-", x, lineY, 0xDDDDDD, false);
                gfx.drawString(this.font, seq, x + bulletIndent, lineY, 0xDDDDDD, false);
                lineY += 10;
            }
            y = lineY + 2;
        }
        y += 8;
        return y;
    }

    private void drawScrollbar(GuiGraphics gfx, int maxScroll) {
        int trackX = innerR - 4;
        int trackT = innerT;
        int trackB = innerB;
        int viewport = Math.max(1, innerB - innerT);

        int thumbH = Math.max(20, viewport * viewport / Math.max(1, contentHeight));
        int maxTravel = viewport - thumbH;
        int thumbY = trackT + (int)(maxTravel * (scrollY / (float)Math.max(1, maxScroll)));

        gfx.fill(trackX, trackT, trackX + 3, trackB, 0x33000000);
        gfx.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0x99FFFFFF);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= innerL && mouseX <= innerR && mouseY >= innerT && mouseY <= innerB) {
            int viewport = Math.max(0, innerB - innerT);
            int maxScroll = Math.max(0, contentHeight - viewport);
            if (maxScroll > 0) {
                int dir = (int)Math.signum(scrollY != 0 ? scrollY : scrollX);
                this.scrollY = clamp(this.scrollY - dir * 24, 0, maxScroll);
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int trackX = innerR - 4;
            if (mouseX >= trackX && mouseX <= trackX + 3 && mouseY >= innerT && mouseY <= innerB) {
                this.draggingScrollbar = true;
                this.dragStartMouseY = (int) mouseY;
                this.dragStartScrollY = this.scrollY;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (draggingScrollbar && button == 0) {
            int viewport = Math.max(1, innerB - innerT);
            int maxScroll = Math.max(0, contentHeight - viewport);
            if (maxScroll > 0) {
                int thumbH = Math.max(20, viewport * viewport / Math.max(1, contentHeight));
                int maxTravel = Math.max(1, viewport - thumbH);
                int deltaMouse = (int) mouseY - dragStartMouseY;
                int newScroll = dragStartScrollY + (int)(deltaMouse * (maxScroll / (float) maxTravel));
                this.scrollY = clamp(newScroll, 0, maxScroll);
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingScrollbar) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
