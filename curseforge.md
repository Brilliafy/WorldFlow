<!-- Rich HTML Companion Banner -->
<div align="center">
  <table style="width: 100%; max-width: 880px; border: 2px solid #3b82f6; border-radius: 12px; background: #0f172a; color: #f8fafc; padding: 14px 20px; margin: 16px auto; box-shadow: 0 4px 14px rgba(59, 130, 246, 0.25);">
    <tr>
      <td width="70" align="center" valign="middle">
        <a href="https://github.com/CleanroomMC/ChunkFlow">
          <img src="https://raw.githubusercontent.com/CleanroomMC/ChunkFlow/main/logo.png" width="60" height="60" alt="ChunkFlow Logo" style="border-radius: 8px; vertical-align: middle;" />
        </a>
      </td>
      <td style="padding-left: 18px; text-align: left; vertical-align: middle;">
        <div style="font-size: 1.15em; font-weight: 800; color: #60a5fa; letter-spacing: 0.5px; margin-bottom: 4px;">
          ⚡ PAIR WITH CHUNKFLOW FOR THE COMPLETE CHUNK PIPELINE!
        </div>
        <div style="font-size: 0.95em; line-height: 1.5; color: #e2e8f0;">
          <b>WorldFlow</b> optimizes <b>generating brand new terrain</b>, eliminates cascading worldgen freezes, and accelerates noise calculation.<br />
          For <b>reading existing chunks from disk up to 5.9x faster</b> with lock-free positional region I/O (<tt>pread64</tt>) and directional Elytra streaming, pair this with our companion mod:
          <a href="https://github.com/CleanroomMC/ChunkFlow" style="color: #93c5fd; font-weight: bold; text-decoration: underline;">👉 ChunkFlow</a>!
        </div>
      </td>
    </tr>
  </table>
</div>

# WorldFlow

**Next-Generation Parallel World Generation, Vectorized Terrain Noise & Cascade Elimination for Minecraft 1.12.2**

---

### 🌍 What is WorldFlow?

**WorldFlow** is an advanced world generation optimization mod for **Minecraft 1.12.2** (running on **Cleanroom** or **Minecraft Forge**). It transforms how the game creates new terrain by introducing multi-threaded parallel generation pipelines, vectorized branchless terrain noise, cross-chunk cascade block staging, and intelligent background pre-generation.

Generating new terrain in Minecraft 1.12.2 has notoriously caused catastrophic server lag spikes, TPS drops, and tick freezes—especially in large modpacks featuring colossal structures and complex biomes like **OpenTerrainGenerator (OTG)**, **Dregora**, **Recurrent Complex**, and **Biomes O' Plenty**.

**WorldFlow solves these problems at their root**, allowing servers and singleplayer clients to generate new terrain at sustained rates exceeding **52+ chunks per second** without stalling game ticks!

---

## ⚡ The Problems WorldFlow Solves

1. **The Cascading Worldgen Death Spiral**: In vanilla and modded Minecraft, structures and custom trees that cross chunk boundaries often place blocks into unloaded neighbor chunks. When vanilla detects this, it synchronously loads and populates that neighbor chunk right away. If that chunk also places blocks into its neighbors, a chain reaction of recursive chunk generation begins. On complex modpacks, a single structure can trigger a cascade of 50+ chunks at once, freezing the server for seconds.
2. **Scalar Noise Bottlenecks**: Computing Perlin noise accounts for nearly half of the CPU time during terrain generation. Vanilla uses scalar nested loops and branching logic that waste CPU cycles.
3. **Single-Threaded Main Thread Generation**: Vanilla and Forge run terrain generation directly on the primary server tick thread. When any player explores new land, entity processing, player actions, and tick updates are forced to wait.

---

## 🌟 Key Features

* 🚀 **True Multi-Threaded Parallel Pipeline**: Offloads base terrain and feature generation to a multi-worker background pool (`cores - 1`), keeping the main game tick completely smooth and lag-free.
* ⚡ **Vectorized Branchless Noise Engine**: Re-engineers `NoiseGeneratorImproved` using branchless gradient math, delivering massive CPU speedups while remaining **100% bit-identical** to vanilla terrain generation.
* 🛡️ **Cross-Chunk Cascade Block Staging**: Blocks placed into unloaded neighbor chunks during population are safely intercepted and staged in memory (`CascadeBlockBuffer`) and on disk (`cascade_staging.dat`). Staged blocks are flushed cleanly once the neighbor chunk loads—eliminating recursive cascading freezes forever!
* 🛌 **Gentle Budgeted Background Pre-Generator**: Conservatively generates new terrain rings around active players during spare server tick time. Automatically backs off whenever server tick times exceed 45 ms, and only works when players are settled to prevent competition with active exploration.
* 🌲 **OTG & Dregora Deep Integration**: Includes asynchronous biome prefetching (`BiomePrefetcher`), 64-way striped structure plotter locks, tuned biome cache limits (saving ~250–300 MB of heap), and thread-safe tree generation guards.
* 🧩 **Extensive Mod Concurrency Protection**: Includes native safety hooks and locks for **DynamicTrees**, **Recurrent Complex**, **Roguelike Dungeons**, **Biomes O' Plenty**, **NuclearCraft**, and **Charm**.

---

## 📊 Performance & Validation

Tested under rigorous radius-32 benchmark suites and long-duration soaks on real Dregora worlds:

| Metric | Vanilla / Standard Forge | WorldFlow 1.0.3 | Improvement |
|---|---|---|---|
| **Radius-32 Generation (4,225 Chunks)** | Severe tick freezes & stalls | **52.7 chunks/s (80.61 s total)** | **Zero Main-Thread Freezes** |
| **Cascading Generation Spikes** | Unchecked recursive loads | Intercepted & Staged | **100% Cascade Elimination** |
| **Terrain Determinism** | Baseline | Bit-identical across 81/81 samples | **Exact Vanilla Output** |
| **OTG Biome Cache Memory** | Oversized (4096 entries) | Optimized (512 entries) | **Saves 250 MB–300 MB Heap** |
| **Dregora World MCA Scan** | Risk of corrupted sectors | 3,109 `.mca` files validated | **0 Bad Headers / 0 Corruption** |

---

## 📦 Requirements & Installation

### Requirements:
* **Minecraft**: `1.12.2`
* **Mod Loader**: [Cleanroom](https://github.com/CleanroomMC/Cleanroom) `0.6.x+` (recommended) or [Minecraft Forge](https://files.minecraftforge.net/) `14.23.5.2864+`
* **Required Dependency**: [**MixinBooter**](https://www.curseforge.com/minecraft/mc-mods/mixinbooter) `10.2` or newer (11.8+ recommended on Forge)
* **Optional**: OpenTerrainGenerator (OTG v9.7) for Dregora worlds
* **Java**: Java 8 through modern Java 21+ runtimes supported.

### Installation:
1. Ensure **MixinBooter** is installed in your `.minecraft/mods` folder.
2. Download `worldflow-1.0.3.jar` and place it in your `mods` folder.
3. *(Recommended)* Pair with [**ChunkFlow**](https://github.com/CleanroomMC/ChunkFlow) for disk loading and Elytra streaming speedups.
4. Launch your server or singleplayer game!

> **Client vs Server**: WorldFlow is primarily a **server-side mod**. It can be installed on dedicated servers without requiring connecting players to have the mod installed. It is also fully functional on the client for singleplayer worlds.

---

## 🛠️ In-Game Commands

Execute `/worldflow` in-game for comprehensive administration and diagnostics:

* `/worldflow noise [stats|toggle|clear]` — View noise counters and toggle branchless evaluations.
* `/worldflow cascade [status|flush|clear|toggle|reload]` — View staged cross-chunk blocks or manually trigger a flush.
* `/worldflow pregen [status|start|stop|toggle|radius <n>|rate <ticks>]` — Configure and monitor gentle background pre-generation.
* `/worldflow pipeline [status|toggle|workers <n>|inflight <n>]` — Monitor or tune the parallel generation pipeline.
* `/worldflow bench <r> <workers> <x> <z> [baseThreads]` — Benchmark world generation speed across a test radius.
* `/worldflow otgstats` — Display live OTG structure caches, BO4 definitions, and staged block numbers.
* `/worldflow unloadarea <cx> <cz> <r>` — Unload and clean up test generation areas from memory.

---

## ⚙️ Configuration Summary

Configuration file: `config/worldflow.cfg`

* **`Enable Parallel Generation`** (Default: `false`): Enables the multi-threaded parallel generation pipeline.
* **`Parallel Worker Threads`** (Default: `0`): Worker count (`0` = auto: `cores - 1`).
* **`Enable Biome Prefetcher`** (Default: `true`): Warms OTG biome arrays a batch ahead on low-priority threads.
* **`enableCascadeFix`** (Default: `true`): Enables cross-chunk block staging to eliminate cascading lag.
* **`stageOTGBlocks`** (Default: `true`): Stages cross-chunk blocks from OTG and Dregora structures.
* **`stageForgeDecorators`** (Default: `true`): Stages cross-chunk blocks from general Forge decorators.
* **`Branchless Gradients`** (Default: `true`): Enables fast vectorized noise math.

---

## ❓ Frequently Asked Questions (FAQ)

**Q: Does WorldFlow change how my world looks?**  
**A:** No! WorldFlow's vectorized noise engine is **100% bit-identical** to vanilla. Terrain height, biomes, rivers, and mountains generate in the exact same positions as vanilla Minecraft.

**Q: What is the difference between WorldFlow and ChunkFlow?**  
**A:**  
- **WorldFlow** is for **generating brand new chunks** when exploring ungenerated territory.
- **ChunkFlow** is for **reading already-generated chunks from disk** (lock-free region I/O, multi-thread executor, and directional Elytra streaming).  
Using both together provides complete, end-to-end chunk optimization!

**Q: Is WorldFlow compatible with Chunk-Pregenerator?**  
**A:** Yes! Chunk-Pregenerator automatically benefits from WorldFlow's vectorized noise engine and cascade freeze prevention.

**Q: Can I use WorldFlow in my modpack?**  
**A:** Yes, absolutely! WorldFlow is licensed under the open-source **GPL-3.0** license and is freely permitted in all public and private modpacks.

---

## 🔗 Links & Community

* **Source Code & Issues**: [GitHub](https://github.com/CleanroomMC/WorldFlow)
* **CleanroomMC Community**: [Cleanroom Discord](https://discord.gg/cleanroommc)
* **Companion Mod**: [ChunkFlow](https://github.com/CleanroomMC/ChunkFlow)
