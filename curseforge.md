<div align="center">
  <table style="display: inline-table; border: 1px solid #3b82f6; border-radius: 8px; background-color: rgba(59, 130, 246, 0.08); padding: 8px 16px; margin: 12px auto;">
    <tr>
      <td style="vertical-align: middle; padding-right: 12px;">
        <a href="https://github.com/Brilliafy/ChunkFlow">
          <img src="https://raw.githubusercontent.com/Brilliafy/ChunkFlow/main/logo.png" width="36" height="36" alt="ChunkFlow Logo" style="border-radius: 6px; vertical-align: middle;" />
        </a>
      </td>
      <td style="vertical-align: middle; text-align: left; font-size: 14px;">
        ⚡ <b>Pair with <a href="https://github.com/Brilliafy/ChunkFlow">ChunkFlow</a></b> for 3x faster chunk loading from disk and smooth exploration without void holes!
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

**WorldFlow solves these problems at their root**, allowing servers and singleplayer clients to generate new terrain at sustained rates of **140 chunks per second** on stock OTG terrain (radius-32 = 4,225 chunks in 30.29 s, Forge 1.12.2, 4C/8T) without stalling game ticks. Full setup and the stock-OTG / no-mods baselines are in [Performance & Validation](#-performance--validation).

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
* 🌲 **Deep Integration with [OTG-Flow](https://github.com/Brilliafy/OTG-Flow)**: Pair with the multi-threaded edition of OpenTerrainGenerator! While stock OTG suffers from mutable biome layer race conditions, thread stalls in `CustomStructurePlotter`, and 27+ GB of coordinate allocation churn, **OTG-Flow** provides thread-confined biome layers, 64-stripe parallel BO4 structure plotting, lock-free per-thread biome caches, and zero-allocation coordinate pooling. Also includes WorldFlow's asynchronous `BiomePrefetcher`.
* 🧩 **Extensive Mod Concurrency Protection**: Includes native safety hooks and locks for **DynamicTrees**, **Recurrent Complex**, **Roguelike Dungeons**, **Biomes O' Plenty**, **NuclearCraft**, and **Charm**.

---

## 📊 Performance & Validation

Tested under rigorous radius-32 benchmark suites and long-duration soaks on real Dregora worlds:

| Metric | Baseline | WorldFlow 1.0.3 | Improvement |
|---|---|---|---|
| **Radius-32, stock OTG 9.7, no optimization mods** | 4,225 chunks in **35.93 s** = 119.3 chunks/s | 4,225 chunks in **30.29 s** = **140.0 chunks/s** | **1.17× (5.64 s saved)** |
| **Vanilla terrain, no mods at all (same driver)** | 8,618 chunks / 80.28 s = **107.4 chunks/s** | — | WorldFlow is **1.30× vanilla** on OTG terrain |
| **Cascading Generation Spikes** | Unchecked recursive loads | Intercepted & Staged | **100% Cascade Elimination** |
| **Terrain Determinism** | Baseline | Bit-identical across 81/81 samples | **Exact Vanilla Output** |
| **OTG Biome Cache Memory** | Oversized (4096 entries) | Optimized (512 entries) | **Saves 250 MB–300 MB Heap** |
| **Dregora World MCA Scan** | Risk of corrupted sectors | 3,109 `.mca` files validated | **0 Bad Headers / 0 Corruption** |

**Benchmark setup (radius-32 numbers above).** Forge `14.23.5.2864`, Java 8, Intel i5-1145G7 (4C/8T), 15 GB RAM, single JVM, `-Xms2G -Xmx3G`, `view-distance=4`, no players online, fresh world (seed `-3418406561478196160`), OTG v9.7 **default preset** (not Dregora), chunk square centered on block `(32000, 32000)` = chunk `(2000, 2000)`, radius 32 = **4,225 chunks**.

- **WorldFlow row:** `/worldflow bench 32 8 2000 2000` → 8 workers (2 base-gen + 6 features), `pipeline=4241 / inline=0`, `BaseGen=9.1 ms`, `Features=9.1 ms` per chunk.
- **Stock-OTG row:** identical 4,225-chunk square, generated by an identical force-load driver (`provideChunk` in ring order) with **only** stock OTG installed — no WorldFlow, no ChunkFlow.
- **Vanilla row:** same driver with **no mods at all**, 80 s steady-state window.

The 52.7 chunks/s figure quoted in earlier builds was the **DregoraRL preset** (far heavier terrain, custom BO3/BO4 objects) on the same laptop, not this OTG default preset.

---

## 📦 Requirements & Installation

### Requirements:
* **Minecraft**: `1.12.2`
* **Mod Loader**: [Cleanroom](https://github.com/CleanroomMC/Cleanroom) `0.6.x+` (recommended) or [Minecraft Forge](https://files.minecraftforge.net/) `14.23.5.2864+`
* **Required Dependency**: [**MixinBooter**](https://www.curseforge.com/minecraft/mc-mods/mixinbooter) `10.2` or newer (11.8+ recommended on Forge)
* **Recommended World Engine**: [**OTG-Flow**](https://github.com/Brilliafy/OTG-Flow) (for Dregora, Biome Bundle, and custom presets; stock OTG v9.7 is also supported)
* **Java**: Java 8 through modern Java 21+ runtimes supported.

### Installation:
1. Ensure **MixinBooter** is installed in your `.minecraft/mods` folder.
2. Download `worldflow-1.0.3.jar` and place it in your `mods` folder.
3. *(Recommended)* Pair with [**ChunkFlow**](https://github.com/Brilliafy/ChunkFlow) for disk loading and Elytra streaming speedups.
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

* **Source Code & Issues**: [GitHub](https://github.com/Brilliafy/WorldFlow)
* **Cleanroom Discord**: [Cleanroom Community Discord](https://discord.gg/cleanroommc)
* **Companion Mods**: [ChunkFlow](https://github.com/Brilliafy/ChunkFlow) (Disk I/O) • [OTG-Flow](https://github.com/Brilliafy/OTG-Flow) (Concurrent OTG World Engine)
