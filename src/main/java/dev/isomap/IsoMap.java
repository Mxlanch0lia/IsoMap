package dev.isomap;

import com.mojang.logging.LogUtils;
import dev.isomap.ping.PingNetwork;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * The part of the mod loaded on both sides. On a server it only relays pings between players;
 * the map itself lives in {@link dev.isomap.client.IsoMapClient}. Nothing here may touch client-only classes.
 */
@Mod(IsoMap.MOD_ID)
public final class IsoMap {
    public static final String MOD_ID = "isomap";
    public static final Logger LOGGER = LogUtils.getLogger();

    public IsoMap(IEventBus modBus) {
        modBus.addListener(PingNetwork::register);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
