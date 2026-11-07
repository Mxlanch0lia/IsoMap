package dev.isomap.client.waypoint;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;

/** Teleports through the game's own command, so the server decides as usual and nothing is possible without cheats. */
public final class WaypointTeleport {
    private static final int COMMAND_PERMISSION_LEVEL = 2;

    private WaypointTeleport() {}

    /** True where the player may use {@code /tp}: cheats on in singleplayer, or operator rights on a server. */
    public static boolean isAllowed(Minecraft mc) {
        LocalPlayer player = mc.player;
        return player != null && mc.getConnection() != null && player.hasPermissions(COMMAND_PERMISSION_LEVEL);
    }

    public static void teleport(Minecraft mc, Waypoint waypoint) {
        teleport(mc, waypoint.dimension, waypoint.x, waypoint.y, waypoint.z);
    }

    /** Whole coordinates: the game puts the player in the middle of that block. */
    public static void teleport(Minecraft mc, String dimensionId, int x, int y, int z) {
        ClientPacketListener connection = mc.getConnection();
        // The dimension comes from a file that can be edited by hand, so it must not smuggle anything into the command.
        ResourceLocation dimension = ResourceLocation.tryParse(dimensionId);
        if (connection != null && dimension != null && isAllowed(mc)) {
            connection.sendCommand("execute in " + dimension + " run tp @s " + x + " " + y + " " + z);
        }
    }
}
