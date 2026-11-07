package dev.isomap.client.waypoint;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Naming and listing of dimensions for the waypoint interface. */
public final class Dimensions {
    private Dimensions() {}

    public static Component label(String dimension) {
        return switch (dimension) {
            case "minecraft:overworld" -> Component.translatable("isomap.dimension.overworld");
            case "minecraft:the_nether" -> Component.translatable("isomap.dimension.nether");
            case "minecraft:the_end" -> Component.translatable("isomap.dimension.end");
            default -> Component.literal(dimension.contains(":") ? dimension.substring(dimension.indexOf(':') + 1) : dimension);
        };
    }

    public static String current(Minecraft mc) {
        return mc.level == null ? "minecraft:overworld" : mc.level.dimension().location().toString();
    }

    /** Dimensions the server announced, plus any the given waypoint already refers to. */
    public static List<String> known(Minecraft mc, String extra) {
        List<String> result = new ArrayList<>();
        ClientPacketListener connection = mc.getConnection();
        if (connection != null) {
            for (ResourceKey<Level> key : connection.levels()) {
                result.add(key.location().toString());
            }
        }
        if (result.isEmpty()) {
            result.add("minecraft:overworld");
            result.add("minecraft:the_nether");
            result.add("minecraft:the_end");
        }
        if (!result.contains(extra)) {
            result.add(extra);
        }
        result.sort(null);
        return result;
    }
}
