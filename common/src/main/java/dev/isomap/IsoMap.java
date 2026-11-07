package dev.isomap;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

/**
 * Shared constants. The loader entry points live in the {@code neoforge} and {@code fabric} projects; on a server the mod
 * only relays pings between players, the map itself is client-only.
 */
public final class IsoMap {
    public static final String MOD_ID = "isomap";
    public static final Logger LOGGER = LogUtils.getLogger();

    private IsoMap() {}

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
