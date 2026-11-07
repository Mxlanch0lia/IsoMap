package dev.isomap.neoforge;

import dev.isomap.platform.Platform;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;

public final class NeoForgePlatform implements Platform {
    private NeoForgePlatform() {}

    /** Both entry points call this, since NeoForge may construct either of them first. */
    static void install() {
        Platform.setIfAbsent(new NeoForgePlatform());
    }

    @Override
    public String loader() {
        return "neoforge";
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean clientCanSend(CustomPacketPayload.Type<?> type) {
        // Client classes are only touched when called, which happens on a client.
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(type);
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    @Override
    public boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return player.connection.hasChannel(type);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    @Override
    public void disableTextShadow(EditBox box) {
        box.setTextShadow(false);
    }
}
