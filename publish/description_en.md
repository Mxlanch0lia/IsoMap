# IsoMap

**A tiny 3D model of your world, right in the corner of your screen.**

Most minimaps show the world as a flat picture. IsoMap shows it the way you actually see it: hills rise, rivers cut through valleys, trees stand on real trunks, and a bridge over a ravine stays a bridge. Grab the camera, turn it, tilt it, and find your way home at a glance.

Works on NeoForge and Fabric, for Minecraft 1.21.1, 26.1.2 and 26.3. Put it on your client and play on any server you like.

## What it does

### A map with real relief
You can tell a mountain from a plain without reading any numbers. Biome colours, water that darkens with depth, tree crowns casting shade on the ground below. If you prefer the classic look, switch 3D off and get a clean top-down map.

### A camera you control
Hold `Left Alt` and move the mouse to turn and tilt the map, from a low angle to straight down. Press `Y` to snap to a top-down view. North stays at the top until you decide otherwise.

### A big map that remembers
Press `M` for a full-screen map of everything you have explored, even from earlier sessions. Drag to look around, double-click to drop a waypoint, right-click for a quick menu.

### Caves that make sense
Go underground and the map switches to a slice of the caves at your height: dark rock, outlined tunnels, floors that warm up near torches and lava. It works in the Nether too.

### Waypoints that keep their height
A waypoint set on a mountain top stays on the mountain top, with a thin pin down to the ground so you can see how high it is. Give waypoints colours and tags, search with `#tag`, and teleport to them if you have cheats on.

### You will find where you died
When you die, a death marker appears automatically with the coordinates and dimension. You can turn it off or limit how many are kept.

### Pings for your friends
Press `G` to put a glowing hologram on whatever you are looking at. Your friends see it through walls and on their map, it follows a mob or player it was placed on, and it fades away by itself after 20 seconds.

On a server with IsoMap (or in a LAN world) pings travel quietly behind the scenes. On a server without it you can let pings go through chat: players with the mod see a hologram, everyone else just sees a line with coordinates.

### Calm, simple interface
Thin borders, light transparency, soft sounds, and buttons that react a little when you hover over them. Everything is set up in game, and the minimap stays visible behind the settings so you see every change right away.

## Default keys

| Key | What it does |
| --- | --- |
| `M` | Full map |
| `K` | Settings |
| `J` | Waypoint list |
| `B` | New waypoint here |
| `G` | Ping what you are looking at (middle mouse button on the full map) |
| `N` | Show or hide the minimap |
| `=` / `-` | Zoom |
| Hold `Left Alt` | Turn the map camera with the mouse, zoom with the wheel |
| `Y` | Top-down view |

You can change any of them in the settings.

## Installation
- **NeoForge:** just drop the jar into `mods`.
- **Fabric:** you also need [Fabric API](https://modrinth.com/mod/fabric-api). [Mod Menu](https://modrinth.com/mod/modmenu) is optional and adds a settings button to the mod list.
- **Server:** not required. Install it there only if you want pings to travel without chat. Players without the mod can still join.

## Good to know
- The map shows only what your game has loaded. The surface you have seen is remembered; caves and the Nether are not.
- Changes to blocks show up on the map within a few seconds.
- Colours come from the vanilla map palette, so blocks from other mods get whatever map colour their authors gave them.
- Other players and mobs are not shown on the map.

## Languages
English, Russian, German, French, Spanish, Portuguese (Brazil), Ukrainian and Simplified Chinese. If you spot an awkward translation, let me know.

## Found a bug?
Write to **mxlanch0lia@gmail.com**. It helps a lot if you include the Minecraft and IsoMap versions, your other mods and, if the game crashed, the crash report or `logs/latest.log`.

## License
MIT. The logo and the sounds were made specifically for this mod.
