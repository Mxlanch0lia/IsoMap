# IsoMap

**A minimalist 2.5D minimap for NeoForge and Fabric.** Your world as a small isometric model in the corner of the screen: real relief, a free camera, and waypoints that keep their height.

Install it on the client and it works on any server. On Fabric it needs Fabric API. The server only needs the mod if you want pings to travel between players invisibly, without chat.

## Features

### 2.5D map
- Isometric terrain with readable relief, biome colours and water depth
- Free camera: turn and tilt with the mouse, from a low angle to straight down
- Zoom, smooth following, north at the top by default
- Real shapes: tree crowns on trunks with shade below, floating blocks, bridges and overhangs instead of pillars down to the ground
- 3D can be turned off for a flat top-down map
- A full-screen map with panning that remembers explored chunks between sessions: click a waypoint to open it, double-click to create one

### Cave view
Underground, the map switches to a section of the caves at your height: dark rock, outlined passages, floors that fade with depth and warm up around torches and lava. It also works in the Nether.

### Waypoints
- Name, position, dimension, colour and visibility
- Shown at their real height, with a thin pin down to the ground
- Tags, with filtering and search (`#tag`)
- Teleport to a waypoint if you have cheats or operator rights
- Saved per world and per server

### Death markers
A marker with coordinates and dimension is created automatically when you die. You can turn this off and limit how many are kept.

### Pings
Press `G` and whatever you are looking at gets a temporary hologram for other players: a beam, a floating diamond with your name and the distance, and a ripple on the ground. It shows through walls and on the map, follows a mob or player it was placed on, and disappears by itself after 20 seconds.
- On a server with the mod, and in a LAN world, pings travel invisibly, within the dimension and team
- On a server without the mod you can turn on sending through chat: players with the mod see a ping instead of the message, everyone else sees a line with coordinates
- Otherwise, on a server without the mod, only you see your ping

### Clean interface
- Plain rectangles, thin borders, light transparency
- Controls grow slightly on hover and flash on press
- Everything scales with GUI scale
- Soft synthesised interface sounds with volume and per-group switches

### Everything in game
A settings screen with five tabs: General, Minimap, Waypoints, Sounds, Controls. Changes apply immediately, and the minimap stays visible behind the panel as a live preview.

## Default keys

| Key | Action |
| --- | --- |
| `M` | Open the full map |
| `K` | Settings |
| `J` | Waypoint list |
| `B` | New waypoint here |
| `G` | Ping what you are looking at (middle mouse button on the full map) |
| `N` | Show or hide the minimap |
| `=` / `-` | Zoom |
| Hold `Left Alt` | The mouse turns the map camera; the wheel zooms |
| `Y` | Top-down view |

All keys can be changed in the settings.

## Good to know
- The map only knows chunks your client has received. The surface you have seen is saved; caves and the Nether are not.
- Block changes appear on the map within a few seconds.
- Colours come from the vanilla map palette and biome tints, so modded blocks use the map colour their authors chose.
- Other players and mobs are not shown on the map.

## Bug reports and contact
Found a bug, or something does not work with another mod? Write to **mxlanch0lia@gmail.com**. Please add the Minecraft and IsoMap versions, your other mods and, if the game crashed, the crash report or `logs/latest.log`.

## Languages
English and Russian.

## License
MIT. The sounds and the logo are original and were made for this mod.
