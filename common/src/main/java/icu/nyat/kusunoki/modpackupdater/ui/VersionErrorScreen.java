package icu.nyat.kusunoki.modpackupdater.ui;

import icu.nyat.kusunoki.modpackupdater.version.VersionGuard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class VersionErrorScreen extends Screen {
    public VersionErrorScreen() {
        super(Component.literal("Incompatible environment"));
    }

    @Override
    protected void init() {
        // Single Quit button centered
        int w = 120;
        int h = 20;
        int x = (this.width - w) / 2;
        int y = this.height / 2 + 40;
        this.addRenderableWidget(Button.builder(Component.literal("Quit Game"), b -> quit())
                .bounds(x, y, w, h)
                .build());
    }

    private void quit() {
        Minecraft mc = Minecraft.getInstance();
        mc.stop();
    }

    @Override
    public void onClose() {
        // Do nothing to force the user to quit
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float delta) {
        this.renderBackground(gfx, mouseX, mouseY, delta);
        int centerX = this.width / 2;
        int y = this.height / 4;

        // Title
        gfx.drawCenteredString(this.font, this.title, centerX, y, 0xFF5555);
        y += 20;

        // Message (may be multi-line)
        String message = VersionGuard.getMessage();
        if (message == null) message = "";
        for (String line : message.split("\\n")) {
            gfx.drawCenteredString(this.font, Component.literal(line), centerX, y, 0xFFFFFF);
            y += 12;
        }

        super.render(gfx, mouseX, mouseY, delta);
    }
}

