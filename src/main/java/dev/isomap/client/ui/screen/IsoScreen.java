package dev.isomap.client.ui.screen;

import dev.isomap.client.sound.UiSound;
import dev.isomap.client.ui.Theme;
import dev.isomap.config.IsoMapConfig;
import javax.annotation.Nullable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shared screen behaviour: open and close sounds, a short fade-and-slide in, and a dim backdrop instead of blur. */
public abstract class IsoScreen extends Screen {
    private static final float OPEN_SECONDS = 0.12f;
    private static final float SLIDE_PIXELS = 5f;
    private static final int BACKDROP = 0x70000000;

    @Nullable
    protected final Screen parent;
    private boolean opened;
    private long openedAt;

    protected IsoScreen(Component title, @Nullable Screen parent) {
        super(title);
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (!opened) {
            opened = true;
            openedAt = System.nanoTime();
            UiSound.OPEN.play();
        }
    }

    protected float openProgress() {
        if (!IsoMapConfig.UI_ANIMATIONS.getAsBoolean()) {
            return 1f;
        }
        float t = Math.min(1f, (System.nanoTime() - openedAt) / 1.0e9f / OPEN_SECONDS);
        return 1f - (1f - t) * (1f - t);
    }

    @Override
    public void onClose() {
        UiSound.CLOSE.play();
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        float t = openProgress();
        var pose = g.pose();
        pose.pushPose();
        pose.translate(0f, (1f - t) * SLIDE_PIXELS, 0f);
        super.render(g, mouseX, mouseY, partialTick);
        renderOverlay(g, mouseX, mouseY, partialTick);
        pose.popPose();
    }

    /** Called by {@link Screen#render} before the widgets. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (minecraft != null && minecraft.level == null) {
            renderPanorama(g, partialTick);
        }
        g.fill(0, -8, width, height + 8, Theme.withAlpha(BACKDROP, openProgress()));
        renderContent(g, mouseX, mouseY, partialTick);
    }

    /** Panels and text below the widgets. */
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY, float partialTick) {}

    /** Things above the widgets. */
    protected void renderOverlay(GuiGraphics g, int mouseX, int mouseY, float partialTick) {}
}
