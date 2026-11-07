package dev.isomap.client.render;

import dev.isomap.client.ui.Theme;
import dev.isomap.config.IsoMapConfig;
import dev.isomap.config.ScreenAnchor;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.biome.Biome;

/** The HUD layer: frame, map image, markers and the info line. All sizes are GUI units, so GUI scale applies by itself. */
public final class HudOverlay {
    private static final int LINE_HEIGHT = 11;
    private static final long FADE_NANOS = 140_000_000L;
    private static final float CAVE_BACKDROP = 0.92f;
    private static final int COMPASS_MARGIN = 12;
    private static final float COMPASS_LETTER_DISTANCE = 6f;

    private static long visibleSince;
    private static boolean wasVisible;
    private static BlockPos biomePos = BlockPos.ZERO;
    private static String biomeName = "";

    private HudOverlay() {}

    public static int clampedSize(Minecraft mc) {
        int limit = Math.min(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight()) - 12 - 2 * COMPASS_MARGIN - 2 * LINE_HEIGHT;
        return Math.max(32, Math.min(IsoMapConfig.SIZE.getAsInt(), limit));
    }

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (!MapRuntime.isHudVisible(mc) || MapRuntime.isScreenViewActive()) {
            wasVisible = false;
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        long now = System.nanoTime();
        if (!wasVisible) {
            wasVisible = true;
            visibleSince = now;
        }
        float fade = IsoMapConfig.UI_ANIMATIONS.getAsBoolean() ? Math.min(1f, (now - visibleSince) / (float) FADE_NANOS) : 1f;

        int size = clampedSize(mc);
        int infoLines = (IsoMapConfig.SHOW_COORDS.getAsBoolean() ? 1 : 0) + (IsoMapConfig.SHOW_BIOME.getAsBoolean() ? 1 : 0);
        int infoHeight = infoLines * LINE_HEIGHT;
        int screenW = mc.getWindow().getGuiScaledWidth();
        int screenH = mc.getWindow().getGuiScaledHeight();
        ScreenAnchor anchor = IsoMapConfig.ANCHOR.get();
        // The compass letters and the info lines live outside the map, so nothing is drawn over the terrain itself.
        int margin = IsoMapConfig.SHOW_COMPASS.getAsBoolean() ? COMPASS_MARGIN : 0;
        int x = anchor.right ? screenW - size - margin - IsoMapConfig.OFFSET_X.getAsInt() : IsoMapConfig.OFFSET_X.getAsInt() + margin;
        int y = anchor.bottom ? screenH - size - margin - infoHeight - IsoMapConfig.OFFSET_Y.getAsInt() : IsoMapConfig.OFFSET_Y.getAsInt() + margin;
        x = Math.max(margin, Math.min(x, screenW - size - margin));
        y = Math.max(margin, Math.min(y, screenH - size - margin - infoHeight));

        float opacity = IsoMapConfig.OPACITY.getAsInt() / 100f * fade;
        float background = IsoMapConfig.BACKGROUND_OPACITY.getAsInt() / 100f * fade;

        // A cave map is drawn on darkness: what lies beyond the known rock should read as more rock, not as the game world.
        float backdrop = MapRuntime.terrain().isSlicing() ? Math.max(background, CAVE_BACKDROP * fade) : background;
        g.fill(x, y, x + size, y + size, Theme.withAlpha(0xFF0E1013, backdrop));
        MapRuntime.hudView().blit(g, x, y, size, size, opacity);

        int cx = x + size / 2;
        int cy = y + size / 2;
        int half = size / 2;
        float partial = delta.getGameTimeDeltaPartialTick(false);
        MapView view = MapRuntime.hudView();
        g.enableScissor(x, y, x + size, y + size);
        MarkerRenderer.drawWaypoints(g, view, cx, cy, half, half, fade, Double.NaN, Double.NaN);
        MarkerRenderer.drawPings(g, view, cx, cy, half, half, fade);
        MarkerRenderer.drawPlayer(g, view, player, partial, cx, cy, fade);
        g.disableScissor();
        g.renderOutline(x, y, size, size, Theme.withAlpha(Theme.BORDER, fade));
        if (margin > 0) {
            MarkerRenderer.drawCompass(g, mc.font, view, cx, cy, half, half, COMPASS_LETTER_DISTANCE, fade);
        }

        if (infoLines > 0) {
            drawInfo(g, mc, player, x, y + size + margin, size, infoLines, fade);
        }
    }

    private static void drawInfo(GuiGraphics g, Minecraft mc, LocalPlayer player, int x, int y, int width, int lines, float fade) {
        Font font = mc.font;
        g.fill(x, y, x + width, y + lines * LINE_HEIGHT, Theme.withAlpha(0xA00E1013, fade));
        int lineY = y + 2;
        if (IsoMapConfig.SHOW_COORDS.getAsBoolean()) {
            String text = player.getBlockX() + " " + player.getBlockY() + " " + player.getBlockZ();
            g.drawString(font, text, x + (width - font.width(text)) / 2, lineY, Theme.withAlpha(Theme.TEXT, fade), false);
            lineY += LINE_HEIGHT;
        }
        if (IsoMapConfig.SHOW_BIOME.getAsBoolean()) {
            String text = font.plainSubstrByWidth(biomeName(mc, player), width - 4);
            g.drawString(font, text, x + (width - font.width(text)) / 2, lineY, Theme.withAlpha(Theme.TEXT_DIM, fade), false);
        }
    }

    private static String biomeName(Minecraft mc, LocalPlayer player) {
        BlockPos pos = player.blockPosition();
        if (biomeName.isEmpty() || pos.distManhattan(biomePos) >= 4) {
            biomePos = pos;
            if (mc.level != null) {
                Holder<Biome> biome = mc.level.getBiome(pos);
                biomeName = biome.unwrapKey()
                        .map(key -> Component.translatable("biome." + key.location().getNamespace() + "." + key.location().getPath()).getString())
                        .orElse("");
            }
        }
        return biomeName;
    }
}
