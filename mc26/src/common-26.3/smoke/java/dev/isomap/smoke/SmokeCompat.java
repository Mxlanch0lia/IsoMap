package dev.isomap.smoke;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;

/** What the QA harness reaches differently on Minecraft 26.1 and 26.3. This is the 26.3 side. */
final class SmokeCompat {
    private SmokeCompat() {}

    static RenderTarget mainTarget(Minecraft mc) {
        return mc.gameRenderer.mainRenderTarget();
    }

    static boolean overlayShown(Minecraft mc) {
        return mc.gui.overlay() != null;
    }

    static ToastManager toasts(Minecraft mc) {
        return mc.gui.toastManager();
    }

    static ChatComponent chat(Minecraft mc) {
        return mc.gui.hud.getChat();
    }

    static void vsyncOff(Minecraft mc) {
        // The option applies itself to the window.
        mc.options.enableVsync().set(false);
    }

    static boolean openToLan(IntegratedServer server, int port) {
        // Cheats for the guest; the host's game mode is already creative.
        return server.publishServer(MinecraftServer.MultiplayerScope.LAN, true, port);
    }
}
