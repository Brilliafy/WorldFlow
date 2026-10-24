<div align="center">

<table style="display: inline-table; border: 1px solid #3b82f6; border-radius: 8px; background-color: rgba(59, 130, 246, 0.08); padding: 8px 16px; margin: 12px auto;">
  <tr>
    <td style="vertical-align: middle; padding-right: 12px;">
      <a href="https://github.com/Brilliafy/ChunkFlow">
        <img src="https://raw.githubusercontent.com/Brilliafy/ChunkFlow/main/logo.png" width="36" height="36" alt="ChunkFlow Logo" style="border-radius: 6px; vertical-align: middle;" />
      </a>
    </td>
    <td style="vertical-align: middle; text-align: left; font-size: 14px;">
      ⚡ <b>Looking for faster disk loading & Elytra flight?</b> Pair with <a href="https://github.com/Brilliafy/ChunkFlow"><b>ChunkFlow</b></a> for lock-free region I/O (<tt>pread64</tt>) & directional streaming!
    </td>
  </tr>
</table>

<br />

<img src="logo.png" alt="WorldFlow Logo" width="130" height="130" />

# WorldFlow

**Parallel World Generation, Vectorized Terrain Noise & Cascade Elimination for Minecraft 1.12.2**

[![Minecraft](https://img.shields.io/badge/Minecraft-1.12.2-blue.svg?style=flat-square&logo=minecraft)](https://minecraft.net/)
[![Platform](https://img.shields.io/badge/Platform-Cleanroom%20%7C%20Forge-orange.svg?style=flat-square)](https://github.com/CleanroomMC)
[![Version](https://img.shields.io/badge/Release-v1.0.3-brightgreen.svg?style=flat-square)](https://github.com/Brilliafy/WorldFlow/releases)
[![License](https://img.shields.io/badge/License-GPL--3.0-blue.svg?style=flat-square)](LICENSE)
[![MixinBooter](https://img.shields.io/badge/Requires-MixinBooter%2010.2+-purple.svg?style=flat-square)](https://www.curseforge.com/minecraft/mc-mods/mixinbooter)
[![Speedup](https://img.shields.io/badge/Speed-52.7%2B%20chunks%2Fs-gold.svg?style=flat-square)](#-validation--benchmarks)

<p align="center">
  <a href="#-why-worldflow">Why WorldFlow?</a> •
  <a href="#-key-features">Key Features</a> •
  <a href="#-architecture-comparison">Architecture</a> •
  <a href="#-validation--benchmarks">Benchmarks</a> •
  <a href="#-installation">Installation</a> •
  <a href="#-commands">Commands</a> •
  <a href="#-configuration">Configuration</a> •
  <a href="#-compatibility--integrations">Compatibility</a>
</p>

</div>

---

## 🌟 Why WorldFlow?

In Minecraft 1.12.2, generating new terrain is the single most expensive operation a server or client can perform. When players travel into ungenerated territory, servers frequently suffer debilitating lag spikes, tick dropouts, or total freezes caused by three fundamental architectural flaws:

1. **The Cascading Worldgen Death Spiral**: During chunk population, structures, trees, and custom decorators (especially in mods like OpenTerrainGenerator, Dregora, and Recurrent Complex) often place blocks across chunk borders into unloaded neighbor chunks. When vanilla detects this, it immediately loads and generates that neighbor chunk on the spot. If that neighbor chunk in turn places blocks into its neighbors, a chain reaction of recursive chunk generation begins. On heavy packs, a single tree or dungeon can trigger dozens of recursive chunk generations, locking the main thread for seconds!
2. **Scalar Terrain Noise Calculation Bottleneck**: Vanilla's `NoiseGeneratorImproved` computes Perlin noise using deep nested loops, branching gradient lookups, and scalar floating-point math. Noise generation accounts for 35%–50% of raw terrain generation CPU time.
3. **Single-Threaded Server Generation**: By default, Forge and vanilla execute all world generation on the single primary server tick thread. When exploration happens, all other game processing (entity AI, tick updates, redstone) grinds to a halt.

**WorldFlow re-engineers world generation from the ground up**, introducing true multi-threaded parallel generation pipelines, vectorized branchless noise, staged cross-chunk cascade buffering, gentle background pre-generation, and deep optimizations for OpenTerrainGenerator (OTG) and Dregora.

---

## ⚡ Key Features

### 🚀 True Multi-Threaded Parallel Generation Pipeline
- Generates base terrain off the main server thread across multiple worker threads (`CPU cores - 1`).
- Utilizes `ProtoChunk` structures and thread-local world views (`ThreadLocalWorldView`) with isolated biome, block, and height caches.
- Multi-staged execution pipeline: divides work into discrete phases (Terrain BaseGen -> Carvers -> Features -> Population -> Publication).
- Off-thread entity and falling-block handling: stages falling blocks (sand, gravel) and entity spawns so worker threads never trigger concurrent modification errors (`ConcurrentModificationException`) on `World.loadedEntityList`.

### ⚡ Vectorized Branchless Terrain Noise Engine
- Vectorized reimplementation of `NoiseGeneratorImproved#populateNoiseArray` with branchless bitwise gradient evaluations.
- Drastically reduces CPU cycle overhead while producing **100% bit-identical** noise output to vanilla Minecraft.
- Worlds generated with WorldFlow match vanilla chunk-for-chunk, biome-for-biome, and block-for-block.

### 🛡️ Cross-Chunk Cascade Block Staging & Freeze Prevention
- Intercepts block placements that target unloaded neighbor chunks during chunk population.
- Staged blocks are stored in a dedicated memory buffer (`CascadeBlockBuffer`) and persisted across restarts (`cascade_staging.dat`).
- Staged blocks are flushed cleanly when the destination chunk is legitimately loaded or when population finishes, allowing colossal structures and modded trees to complete normally **without ever triggering recursive chunk generation**.

### 🛌 Gentle Budgeted Background Pre-Generator
- Conservatively generates rings of chunks around active players during spare server tick time.
- **Tick Budgeting**: Automatically backs off and halts work whenever the previous server tick exceeded `Max Tick Time` (default: 45 ms).
- **Player Stillness Detection**: Waits for players to be stationary before doing background work so active exploration is never penalized.
- **Safety Margin**: Only works within a ring between an inner dead-zone (e.g. 12 chunks) and an outer radius (e.g. 32 chunks), avoiding competition with active player view distances.

### 🌲 OpenTerrainGenerator (OTG v9.7) & Dregora Optimizations
- **`BiomePrefetcher`**: Asynchronously warms OTG biome arrays a batch ahead of the active generation wavefront on background threads, eliminating feature-stage stalls.
- **Striped Coordinate Locks**: Replaces coarse 100x100 region locks with 64-way striped locks on `CustomStructurePlotter`, enabling parallel structure plotting.
- **Cache Optimization**: Re-tunes OTG's oversized global biome cache from 4,096 down to 512 entries, slashing steady-state memory consumption by 250 MB–300 MB without missing hits.
- **Thread-Safe Tree Generation**: Protects `ForgeWorld.world` and `placeTree` from race conditions and stale thread reads.

### 🧩 Broad Mod Concurrency Isolation
- Automated classification and safety levels (`PARALLEL_SAFE`, `SERIALIZE`, `UNSAFE`) for third-party mod generators.
- Native locks and isolation for **DynamicTrees** (`DynamicTreesProviderRegistryMixin` listener race guard), **Recurrent Complex** (`MazePool`, concurrent structure data), **Biomes O' Plenty**, **Roguelike Dungeons**, **NuclearCraft**, and **Charm**.
- Preserves chest loot tables and TileEntities during cross-chunk structure generation.

### 🎯 Deterministic Mode Option
- Running with `-Dworldflow.deterministic=true` configures a deterministic single-worker pipeline preserving exact chunk execution order for bit-identical structure and decoration reproducibility across test runs.

---

## 📊 Architecture Comparison

```mermaid
flowchart TD
    subgraph Vanilla_OTG["Vanilla & OTG (Cascading Stalls)"]
        V1["Populate Chunk (X, Z)"] --> V2["Structure places block in (X+1, Z)"]
        V2 --> V3["Target chunk is unloaded!"]
        V3 --> V4["FORCED SYNCHRONOUS LOAD"]
        V4 --> V5["Generate & Populate (X+1, Z) on Main Thread"]
        V5 --> V6["(X+1, Z) triggers (X+2, Z)... RECURSIVE FREEZE"]
    end

    subgraph WorldFlow_Pipeline["WorldFlow (Staged Parallel Pipeline)"]
        W1["Parallel Workers (BaseGen / Features)"] --> W2["Structure places block in (X+1, Z)"]
        W2 --> W3["Cascade Interceptor: Target unloaded?"]
        W3 -->|Yes| W4["Stage into CascadeBlockBuffer (Memory & DAT)"]
        W4 --> W5["Current Chunk Finishes Smoothly (0 Lag Spikes)"]
        W5 --> W6["When (X+1, Z) Loads: Staged Blocks Applied Cleanly"]
    end
```

---

## 📈 Validation & Benchmarks

WorldFlow has undergone extensive soak and stress testing on massive, real-world Dregora worlds and synthetic multi-thousand chunk areas:

| Test Run | Chunks Generated | Error Counter (CME / NPE / Fallbacks) | Performance & Heap |
|---|:---:|:---:|---|
| **Radius-32 Cold Smoke** | 4,225 chunks | **0 Errors, 0 CMEs, 0 Stalls** | **52.7 chunks/s** (80.61 s total) |
| **Repeated Radius-32 Runs** | 21,125 chunks | **0 Fallbacks, 0 Crashes** | Steady heap under 5.2 GB / 12 GB |
| **Contiguous Radius-16 Soak** | 27,000+ chunks | **0 CMEs, 0 Self-Heals** | 4.4–6.3 GB stable with ZGC |
| **Dregora Backup MCA Scan** | 2,639,510 chunk slots | **0 Bad Headers / 0 Corrupted Sectors** | 3,109 `.mca` files validated |
| **Terrain Determinism** | 81/81 sample points | **100% Bit-Identical** | Biomes, heightmaps & sections match vanilla |

---

## 📦 Installation

### Requirements
- **Minecraft**: `1.12.2`
- **Mod Loader**: [Cleanroom](https://github.com/CleanroomMC/Cleanroom) `0.6.x+` (recommended) or [Minecraft Forge](https://files.minecraftforge.net/) `14.23.5.2864+`
- **Required Dependency**: [MixinBooter](https://www.curseforge.com/minecraft/mc-mods/mixinbooter) `10.2` or newer (11.8+ recommended on Forge)
- **Optional**: OpenTerrainGenerator (OTG v9.7) for Dregora worlds
- **Java**: Java 8 through modern Java 21+ runtimes supported.

### Setup Instructions
1. Ensure **MixinBooter** (10.2+) is present in your `mods/` directory.
2. Place `worldflow-1.0.3.jar` into your `mods/` folder.
3. *(Recommended)* Pair with [**ChunkFlow**](https://github.com/Brilliafy/ChunkFlow) for disk I/O and player chunk streaming speedups.
4. Start your server or singleplayer client.

> [!NOTE]
> WorldFlow is primarily a **server-side optimization mod**. It can be installed on clients for singleplayer worlds, or on dedicated servers without requiring players to install it.

---

## 🛠️ Commands

All commands can be executed by operators (or configured in permissions):

| Command | Subcommands & Usage | Description |
|---|---|---|
| `/worldflow noise` | `stats`, `toggle`, `clear` | View noise evaluation counters and toggle branchless gradient calculation. |
| `/worldflow cascade` | `status`, `flush`, `clear`, `toggle`, `reload` | Check number of currently staged cross-chunk blocks, flush pending blocks, or reload cache. |
| `/worldflow pregen` | `status`, `start`, `stop`, `toggle`, `radius <n>`, `rate <ticks>` | Monitor or control background pre-generation parameters on the fly. |
| `/worldflow pipeline` | `status`, `toggle`, `workers <n>`, `inflight <n>` | Monitor in-flight pipeline generations and tune worker threads dynamically. |
| `/worldflow bench` | `<radius> <workers> <x> <z> [baseThreads]` | Run an automated world generation benchmark over a given radius. |
| `/worldflow otgstats` | — | Displays live OTG structure cache regions, BO3/BO4 definitions, and staged chunk statistics. |
| `/worldflow unloadarea`| `<cx> <cz> <radius>` | Flushes and unloads pregenerated test areas from memory. |
| `/worldflow hashchunk` | `<x> <z> [terrain]` | Computes canonical SHA-256 hash over blocks, biomes, heightmaps, and tile entities for determinism audits. |

---

## ⚙️ Configuration Reference

File: `config/worldflow.cfg`

### `Concurrency` (Parallel Pipeline)
```properties
"Concurrency" {
    # Master toggle for parallel chunk generation pipeline.
    B:"Enable Parallel Generation"=false

    # Number of parallel worker threads (0 = auto: CPU cores - 1).
    I:"Parallel Worker Threads"=0

    # Maximum concurrent chunks in-flight across all generation pipeline stages.
    I:"Max In-Flight Generations"=96

    # Warm OTG biome arrays a batch ahead of the wavefront on low-priority threads.
    B:"Enable Biome Prefetcher"=true

    # Enable concurrent OTG/Dregora terrain generation via per-worker providers.
    B:"Enable Concurrent OTG Terrain"=true

    # Force falling blocks (sand, gravel) to fall instantly during generation.
    B:"Force Instant Falling During Generation"=true

    # Thread-local generation-world isolation barrier.
    B:"Enable ThreadLocal Isolation"=true

    # Serialize every IWorldGenerator not explicitly whitelisted.
    B:"Serialize Unknown Generators"=true
}
```

### `Cascade Fix` (Staging Engine)
```properties
"Cascade Fix" {
    # Enable the chunk cascade prevention engine.
    B:enableCascadeFix=true

    # Stage cross-chunk block placements from OpenTerrainGenerator (OTG / Dregora).
    B:stageOTGBlocks=true

    # Stage cross-chunk block placements from general Forge decorators.
    B:stageForgeDecorators=true

    # Maximum staged blocks permitted per chunk before capping.
    I:maxStagedBlocksPerChunk=16384

    # Persist staged blocks across game restarts into world/data/cascade_staging.dat.
    B:persistStagedBlocks=true
}
```

### `Noise` (Vectorized Math)
```properties
"Noise" {
    # Master toggle for vectorized noise engine.
    B:"Enable Mod"=true

    # Use branchless fast gradient evaluations.
    B:"Branchless Gradients"=true
}
```

### `Pregenerator` (Budgeted Background Pre-Gen)
```properties
"Pregenerator" {
    # Master toggle for background pre-generation.
    B:"Enable Mod"=true

    # Outer radius (in chunks) around each player to pre-generate.
    I:"Radius (chunks)"=32

    # Inner dead-zone radius (in chunks) never touched by pre-gen.
    I:"Inner Radius (chunks)"=12

    # Tick delay between generated chunks (raise to be gentler).
    I:"Interval (ticks per chunk)"=40

    # Max tick duration (in ms) before pre-generator backs off.
    D:"Max Tick Time (ms)"=45.0

    # Wait until players are stationary before doing background work.
    B:"Only When Players Settled"=true
}
```

---

## 🧩 Compatibility & Integrations

| Mod / System | Compatibility | Description |
|---|:---:|---|
| **[ChunkFlow](https://github.com/Brilliafy/ChunkFlow)** | 🌟 **Paired Mod** | **Recommended companion**: ChunkFlow optimizes disk reads and network streaming, while WorldFlow optimizes terrain generation. |
| **OpenTerrainGenerator (OTG v9.7)** | ✅ Full Integration | Biome prefetcher, striped plotter locks, and structure memory reductions. |
| **Dregora / Dregora RL** | ✅ Full Integration | Eliminates cascading world generation freezes from custom structures. |
| **DynamicTrees** | ✅ Native Hook | Thread-safe Poisson disc provider registry lock preventing race conditions. |
| **Biomes O' Plenty** | ✅ Tested Safe | Verified safe execution path with variant guards. |
| **Recurrent Complex** | ✅ Native Hook | Thread-safe maze executor and concurrent structure map hooks. |
| **Roguelike Dungeons** | ✅ Native Hook | Recreates missing TileEntities to ensure chest loot tables are preserved. |
| **NuclearCraft** | ✅ Isolated | Thread-safe radiation helpers. |
| **Chunk-Pregenerator** | ✅ Compatible | External pregeneration tools benefit from cascade fixes and vectorized noise. |

---

## 🏗️ Building from Source

### Prerequisites
- JDK 17, 21, or 25 (for running Gradle 9+)
- Target toolchain compiles Java 8 bytecode automatically

### Build Instructions
```bash
# Clone the repository
git clone https://github.com/Brilliafy/WorldFlow.git
cd WorldFlow

# Build the mod jar using Gradle
./gradlew build

# The output jar will be located at:
# build/libs/worldflow-1.0.3.jar
```

---

## 🤝 Contributing

Contributions, bug reports, and optimizations are welcome!
- Please read our [**Contributing Guidelines**](CONTRIBUTING.md) before opening a pull request.
- Verify your changes with `./gradlew build` and automated worldgen benchmarks.
- Report issues and suggest features on the [**GitHub Issue Tracker**](https://github.com/Brilliafy/WorldFlow/issues).

---

## 📜 License & Credits

- **Author**: Brilliafy
- **Organization**: [CleanroomMC](https://github.com/CleanroomMC)
- **Credits**: CleanroomMC, Luke100000 (inspiration from the original Cascade mod concept)
- **License**: [GNU General Public License v3.0 (GPL-3.0)](LICENSE)

*Minecraft is a registered trademark of Mojang Synergies AB. WorldFlow is an open-source optimization project not affiliated with Mojang or Microsoft.*
