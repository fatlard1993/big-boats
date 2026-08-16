# Big Boats

A Minecraft Fabric mod that lets you build and sail multi-block ships. Build any structure, add a helm, christen it, and sail away.

Requires the **Pandorical** mod client-side. Ships are plain server-side entities with no vanilla-client disguise: their blocks are rendered to Pandorical clients as a single batch-rendered structure (via Pandorical's `structures()` API), and camera pull-back/third-person perspective while piloting is pushed automatically through Pandorical's camera API. There is no vanilla-client compatibility and no separate optional client mod; Pandorical is required to see or pilot a ship at all.

## Features

### Ship Building
- **Build any structure** using standard Minecraft blocks
- Ships can be up to **2,000 blocks** in size
- Supports **block entities** (chests, furnaces, signs); contents persist
- **Item frames and paintings** travel with the ship and restore on dock
- **Doors, trapdoors, and fence gates** remain interactive while sailing

### The Helm
- Craft and place a **Helm block** as your ship's wheel
- The helm determines the ship's forward direction
- Right-click the helm to board your ship

### Christening
- Craft a **Christening Bottle** to launch your ship
- Throw the bottle at any part of your ship structure
- Name the bottle in an anvil to name your ship

### Sailing
- **WASD controls**: W/S for forward/back thrust, A/D to rotate
- Ships have **momentum-based physics**; they accelerate and coast
- **Collision detection** stops ships at terrain and other ships (breaks through plants)
- Ships **snap to grid** when you dismount for clean docking

### Docking System
- Ships **auto-dock** when you dismount (places real blocks back)
- Ships **auto-undock** when you board (converts to a rendered Pandorical structure)
- **Block absorption**: Small structures touching your ship may be absorbed when undocking
- **Grounding detection**: Can't sail if connected to a large landmass
- **Occupied check**: Only one pilot at a time
- **Structure damage detection**: Can't undock if the ship structure is broken

## Items

### Christening Bottle
Thrown item that converts a block structure into a sailable ship entity.

**Recipe** (shapeless):
- 1x Heart of the Sea
- 1x Glass Bottle

### Helm Block
The ship's wheel, required for every ship. Place it facing the direction you want to sail.

**Recipe:**
```
    [S]
[S][I][S]
[P][P][P]
```
S = Stick, I = Iron Ingot, P = Any Planks

## Controls

| Key | Action |
|-----|--------|
| W | Accelerate forward |
| S | Accelerate backward |
| A | Rotate left |
| D | Rotate right |
| Shift | Dismount (docks the ship) |

## Technical Details

- **Minimum ship size**: 2 blocks (helm + at least one other)
- **Maximum ship size**: 2,000 blocks
- Ships track water surface height as they move
- **Ship lighting**: Light-emitting blocks on ships place invisible light blocks that move with the ship
- Collision checks all block corners to prevent clipping
- Hull-only collision optimization skips interior blocks
- Crash recovery: ships sailing when the server stops are force-docked on restart with all blocks restored

## Requirements

Targets the Minecraft, Fabric Loader, Fabric API, and Java versions declared in this mod's `gradle.properties`; check there for the exact currently-supported version.

## Pandorical

Two things route through it:

- **Ship rendering**: a ship's blocks are rendered as a single batch-rendered Pandorical structure, posed and moved each tick to follow the ship's position and rotation. The ship entity itself is registered with Pandorical's `"invisible"` renderer and draws nothing on its own; only the structure is visible.
- **Piloting camera**: camera pull-back distance and third-person-back perspective while piloting are pushed directly to the player via Pandorical's camera API on mount/dismount.

## Installation

Install alongside its declared dependencies (see `fabric.mod.json`), including Pandorical client-side.

## Download

Get the latest release from [GitHub Releases](https://github.com/justfatlard/big-boats/releases).

## Building from Source

Big Boats builds against Pandorical's live source, not a published artifact: `settings.gradle` includes `../pandorical`. Clone both side by side or the build fails before it starts.

```bash
git clone https://github.com/justfatlard/pandorical.git
git clone https://github.com/justfatlard/big-boats.git
cd big-boats
./gradlew build
```

The built JAR will be in `build/libs/`.

## Commands

| Command | Permission | Description |
|---------|-----------|-------------|
| `/bigboats cleanup-lights` | GAMEMASTERS | Removes orphaned light blocks within 50 blocks of you. Active ships re-place theirs next tick. |

## Known Limitations

- Ladders don't function for climbing while sailing
- Single driver only
- No ship ownership model (any player can mount any ship)

## Issues & Support

Report bugs and request features on [GitHub Issues](https://github.com/justfatlard/big-boats/issues).

## License

MIT License; see the LICENSE file for details.
