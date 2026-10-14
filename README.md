# World Flow

![WorldFlow](logo.png)

World generation optimization for Minecraft 1.12.2 on Cleanroom or Minecraft Forge.

World Flow makes terrain noise cheaper, stops a common world generation stall, and can generate chunks
ahead of the player so that new terrain is ready before they arrive.

## Fast terrain noise

Terrain noise is one of the hottest parts of world generation. World Flow reimplements
`NoiseGeneratorImproved#populateNoiseArray` with a tighter loop that keeps the same result as vanilla.
The output is intentionally identical, so worlds generated with the mod match worlds generated
without it.

## OTG and Dregora cascade fix

During population, decorators and structures sometimes place blocks in chunks that are not loaded
yet. Minecraft then loads and generates those neighbouring chunks on the spot. If a modded structure
does that across several chunks, the server can spend seconds generating a chain of chunks while the
game is frozen.

World Flow stages block placements that target an unloaded chunk instead of letting them trigger a
load. The staged blocks are placed once the target chunk is loaded, or when population of the current
chunk finishes. Structures still end up complete, but the recursive load chain does not happen.

The staging is on by default. It is guarded against materials that are not Forge material data, and
the flush command takes a snapshot of the loaded chunk list so it cannot throw while flushing.

## Background pre-generation

New chunks are generated on the server thread, so the only way to avoid a stall when a player moves
into new terrain is to have the chunks ready beforehand. World Flow pre-generates a ring around each
player, slowly, during spare server time.

It is deliberately conservative:

- It runs at the end of the server tick, at most one chunk per `Interval (ticks per chunk)`.
- It backs off completely whenever the previous tick ran longer than `Max Tick Time (ms)`.
- It only works in the ring between the inner and outer radius, so it never competes with the chunks a
  player is loading right now.
- It never touches a chunk that is loaded, and it skips chunks that are already saved.
- Optionally it waits until no player has moved for a while before doing anything.
- It rests after scanning a full ring with nothing to do.

Generated chunks are queued for unload and saved normally. When a player later reaches them, they load
from disk.

## Requirements

- Minecraft 1.12.2
- Minecraft Forge 14.23.5.x or Cleanroom 0.6.x
- MixinBooter 10 or newer
- OpenTerrainGenerator is optional. The cascade fix needs it.
- Java 8

## Installation

Put `worldflow-<version>.jar` into your `mods` folder. Install it on the server. The pre-generator and
the cascade fix are server side; the noise change applies wherever terrain is generated.

## Configuration

File: `config/worldflow.cfg`

### Noise

| Option | Default | Description |
|---|---|---|
| Enable Mod | true | Master toggle for the noise reimplementation. |
| Branchless Gradients | true | Use the branchless gradient evaluation. |

### Cascade Fix

| Option | Default | Description |
|---|---|---|
| Enable Cascade Fix | true | Stage cross chunk block placements. |
| Stage OTG Blocks | true | Stage placements from OTG and Dregora. |
| Stage Forge Decorators | true | Stage placements from Forge decorators. |
| Log Prevented Cascades | false | Log each prevented cascade. |
| Max Staged Blocks Per Chunk | 16384 | Safety cap for staged blocks per chunk. |
| Persist Staged Blocks | true | Save staged blocks across restarts. |

### Pregenerator

| Option | Default | Description |
|---|---|---|
| Enable Mod | true | Master toggle for pre-generation. |
| Radius (chunks) | 32 | Outer radius around each player. |
| Inner Radius (chunks) | 12 | Dead zone that is never pre-generated. |
| Interval (ticks per chunk) | 40 | Delay between chunks. Raise this to be gentler. |
| Max Candidate Checks Per Tick | 64 | Candidate chunks inspected per work tick. |
| Max Tick Time (ms) | 45.0 | Back off above this tick time. |
| Only When Players Settled | true | Wait for no player movement before working. |
| Idle Ticks Required | 100 | How long players must be still. |
| Idle Movement Threshold | 0.01 | Movement per tick that counts as moving. |
| Empty Pass Cooldown (ticks) | 600 | Rest after a full ring with no work. |
| Overworld Only | true | Only pre-generate dimension 0. |

## Commands

`/worldflow <noise|cascade|pregen> ...`

- `/worldflow noise [stats|toggle|clear]`
- `/worldflow cascade [status|flush|clear|toggle|reload]`
- `/worldflow pregen [status|start|stop|toggle|radius <n>|inner <n>|rate <ticks>|reset]`

## Notes and limitations

- New terrain generation cannot be safely parallelized in 1.12.2 without a full rewrite of the light
  and generation engines, and OTG generation is single threaded by design. Pre-generation is the safe
  way to hide that cost, and it is intentionally slow.
- The pre-generator uses the normal generation path, so structures, population and lighting behave
  exactly as vanilla.

## Credits

Written by Brilliafy. The cascade staging approach follows the Cascade mod by Luke100000.

## License

GPL-3.0. Copyright (C) 2026 Brilliafy. See the LICENSE file.
