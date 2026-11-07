package dev.isomap.fabric;

import dev.isomap.platform.Platform;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public final class FabricPlatform implements Platform {
    private FabricPlatform() {}

    /** Both entry points call this; Fabric runs the main one first, but nothing depends on that. */
    static void install() {
        Platform.setIfAbsent(new FabricPlatform());
    }

    @Override
    public String loader() {
        return "fabric";
    }

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    // The client methods are only ever called on a client; a dedicated server, where these classes are missing, never runs them.

    @Override
    public boolean clientCanSend(CustomPacketPayload.Type<?> type) {
        return ClientPlayNetworking.canSend(type);
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        ClientPlayNetworking.send(payload);
    }

    @Override
    public boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return ServerPlayNetworking.canSend(player, type);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        ServerPlayNetworking.send(player, payload);
    }

    @Override
    public void disableTextShadow(EditBox box) {
        ((TextShadow.Switch) box).isomap$setTextShadow(false);
    }
}
