package dev.isomap.smoke;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameType;

/** What the QA harness reaches differently on Minecraft 26.1 and 26.3. This is the 26.1 side. */
final class SmokeCompat {
    private SmokeCompat() {}

    static RenderTarget mainTarget(Minecraft mc) {
        return mc.getMainRenderTarget();
    }

    static boolean overlayShown(Minecraft mc) {
        return mc.getOverlay() != null;
    }

    static ToastManager toasts(Minecraft mc) {
        return mc.getToastManager();
    }

    static ChatComponent chat(Minecraft mc) {
        return mc.gui.getChat();
    }

    static void vsyncOff(Minecraft mc) {
        mc.options.enableVsync().set(false);
        mc.getWindow().updateVsync(false);
    }

    static boolean openToLan(IntegratedServer server, int port) {
        return server.publishServer(GameType.CREATIVE, true, port);
    }
}
