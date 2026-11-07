package dev.isomap.ping;

import dev.isomap.IsoMap;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A ping on its way through the server. What a client sends about itself is only a claim:
 * the server replaces sender and dimension with what it knows before passing the ping on.
 */
public record PingPayload(UUID sender, String senderName, ResourceLocation dimension, double x, double y, double z, int entityId)
        implements CustomPacketPayload {
    public static final int NO_ENTITY = -1;
    private static final int MAX_NAME_LENGTH = 32;

    public static final Type<PingPayload> TYPE = new Type<>(IsoMap.id("ping"));
    public static final StreamCodec<FriendlyByteBuf, PingPayload> CODEC = CustomPacketPayload.codec(PingPayload::write, PingPayload::read);

    private static PingPayload read(FriendlyByteBuf buf) {
        return new PingPayload(buf.readUUID(), buf.readUtf(MAX_NAME_LENGTH), buf.readResourceLocation(),
                buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readVarInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUUID(sender);
        buf.writeUtf(senderName, MAX_NAME_LENGTH);
        buf.writeResourceLocation(dimension);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeVarInt(entityId);
    }

    @Override
    public Type<PingPayload> type() {
        return TYPE;
    }
}
