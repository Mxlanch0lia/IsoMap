package dev.isomap.platform;

import java.nio.file.Path;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/**
 * The little the shared code needs from the mod loader: folders and the optional ping channel. Each loader sets its
 * implementation first thing when the mod starts, before anything else of the mod runs.
 */
public interface Platform {
    /** "neoforge" or "fabric". */
    String loader();

    Path gameDir();

    Path configDir();

    /** Whether the server we are connected to has the mod, so a payload of this type can go to it. */
    boolean clientCanSend(CustomPacketPayload.Type<?> type);

    void sendToServer(CustomPacketPayload payload);

    /** Whether this player's client has the mod and listens for payloads of this type. */
    boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type);

    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    /** Plain Minecraft always draws a text field's text with a shadow; the mod's flat fields want none. Client only. */
    void disableTextShadow(EditBox box);

    static Platform get() {
        Platform platform = Holder.instance;
        if (platform == null) {
            throw new IllegalStateException("IsoMap platform used before the loader set it");
        }
        return platform;
    }

    /** Sets the platform unless one is set already, so every entry point of a loader may call it. */
    static void setIfAbsent(Platform platform) {
        synchronized (Holder.class) {
            if (Holder.instance == null) {
                Holder.instance = platform;
            }
        }
    }

    final class Holder {
        private static volatile Platform instance;

        private Holder() {}
    }
}
