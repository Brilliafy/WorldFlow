# Milestone: clean wavefront (2026-10-23)

## Result

Two independent cold-world runs of the 4,225-chunk gate on the Vast instance
(instance `57374174`, `DregoraRL-test`, 12 GB heap):

| center | time | rate | biomes | heightmap | sections | CME / AIOOBE / NPE |
|--------|------|------|--------|-----------|----------|--------------------|
| [42400, 42400] | 52.79 s | 80.3 c/s | 256/256 | 256/256 | 256/256 | 0 |
| [42500, 42500] | 57.71 s | 73.7 c/s | 256/256 | 256/256 | 256/256 | 0 |
| [42700, 42700] | 63.17 s | 67.1 c/s | 256/256 | 256/256 | 256/256 | 0 |

- Heap after run: ~4.3 GB used / 12 GB max (ZGC).
- CPU: the container quota is **17.28 cores** (`/sys/fs/cgroup/cpu.max` = 1728000/100000).
  Mid-run cgroup samples: 17.22 and 16.58 cores used (~96-100% of quota), with
  88-99 throttling events per 10 s. CPU is saturated against the allocation.
  (`nproc` reports 72 because it sees the host, not the quota.)
- No `Off-thread error`, no `StallDetector` entries, no partially generated chunks.
- Region/data directories wiped before every run.

## What changed

- Row-major 2D wavefront (no Stage-1 priming, no global phase barriers).
- Local 3x3 phase DAG with exact stencil refcounts.
- Bounded (256) neighbour primer cache; halo chunks stop at CARVERS.
- Eager OTG `TreeGen.loadTrees` on the first request, published through a
  volatile flag (no half-initialised arrays).
- OTG biome chain made thread-confined:
  - `Layer` keeps chunk/group/world seed scratch in a `ThreadLocal`
    (`src/otgpatch/.../layers/Layer.java`).
  - `LayerBiomeInBiome` passes the per-isle seed instead of mutating shared state.
  - `CachedBiomeGenerator` uses a per-thread FIFO chunk cache.
- Published-chunk writes are staged (blocks, tile entities, entities) and flushed
  on the main thread; the OTG `setBlock` path no longer calls `Chunk.setBlockState`
  directly.
- `World.loadedEntityList` replaced with a snapshot-iterating list;
  `World.countEntities` iterates a snapshot.
- RecurrentComplex `RCWorldgenMonitor.actions` and Charm village maps/list are
  thread-local / concurrent.
- Fallback path is fail-closed: a chunk is regenerated fully on the main thread,
  never published from a partial primer.

## Required mod config

`config/noisethreader.cfg`:

```
B:"Multithread BetterCaves Noise Generation"=false
```

NoiseThreader's OTG mixin otherwise serializes every biome-cache chunk behind a
single lock, which dominates dungeon structure scans.

## Artifacts

- `dist/worldflow-1.0.2-wavefront-clean-20261023.jar`
- `dist/OpenTerrainGenerator-1.12.2-v9.7-wavefront-clean-20261023.jar`
- `dist/worldflow-otgpatch-wavefront-clean-20261023.jar`
- `dist/SHA256SUMS-wavefront-clean-20261023.txt`

Commit: `9d47b8a` on `feature/wavefront-3x3-refcount`
Tag: `milestone-wavefront-clean-20261023`

## Still open

- Copy the real `DregoraRL` world to the Vast server and restore `level-name`.
- Re-check the 128 in-flight window under 8-12 GB on the real world.
- Structure AABB eviction and biome cache bounding can be revisited after the
  real-world run; the hot memory was inside the budget in these runs.

# Production wrap-up (2026-10-23, later)

## Zero-config NoiseThreader guard

`NoiseThreaderGuard` forces `ForgeConfigHandler.server.multithreadBetterCavesNoise = false`
in memory at preInit/init/server-start (reflection, failure-tolerant). Users no longer
need to edit `config/noisethreader.cfg`. BetterCaves scalar optimizations stay active;
only the nested thread pools / biome-cache lock are neutralized.

Log line:

```
[WorldFlow] Detected NoiseThreader: Automatically neutralized intra-chunk thread pools
to prevent thread oversubscription. Retaining BetterCaves scalar optimizations.
```

A config file set to `=true` still logs the guard line and runs disabled.

## Singleplayer lifecycle

- `FMLServerStoppingEvent`: pools `shutdown()`, `GenerationScheduler.resetRuntimeState()`
  drops in-flight holders, retained/neighbour protos, publish queues, staged blocks,
  OTG biome/tree/spawner caches. Watchdog flag cleared.
- `FMLServerStartingEvent`: `ensureWatchdog()` re-arms on the freshly created pool.
- Worker/scheduler threads are daemon threads, so a client JVM cannot be pinned by an
  orphaned pool if a world exits mid-generation.

## Profiler audit (asprof 4.6, Quadro P4000 host, 17.28-core quota)

Profiled cold run (cpu 20 s + alloc 20 s). Profiling itself slows the run, so only
relative shares are meaningful.

Top CPU leaves:

| % | method |
|---|--------|
| 4.26 | `SimpleSurfaceGenerator.spawnColumn` |
| 2.62 | `Layer.initChunkSeed` (our thread-confined scratch) |
| 2.49 | `NoiseGeneratorPerlin.grad` |
| 1.92 | `LayerBiomeInBiome.getInts` |
| 1.85 | `Layer.getBiomeFromLayer` |
| 1.65 | `ChunkProviderOTG.generateTerrainA` |
| 1.51 | `ThreadLocal.get` |
| 1.28 | `GenerationWorkerContext.lookup` |

Top allocation sites:

| % | type | caller |
|---|------|--------|
| 25.19 | `BlockPos` | Roguelike/fishing/Ice&Fire/structures |
| 20.77 | `double[]` | BetterCaves noise |
| 7.30 | `BO3BlockFunction` | OTG BO3 spawn |
| 5.29 | `NoiseTupleNew` | BetterCaves |
| 4.79 | `Long` | boxed neighbour/proto keys |

Earlier profile had `ChunkCoordinate` at 60.08 % of allocations (27.3 GB in 25 s),
almost all from `BO3.trySpawnAt`. Patched with a 256-slot per-thread canonical cache:
`ChunkCoordinate` is gone from the top-15 allocation sites.

Quick wins applied:
- canonical `ChunkCoordinate.fromBlockCoords` (otgpatch).
- publish marks the chunk immutable before the main-thread flush; the flush holds the
  `ProtoChunk` lock (closes a palette-resize window).
- phase-dep gate made advisory (10 s) and released on the existing-chunk publish path
  (previously leaked, stranded the wavefront tail).
- no per-publish global entity/tick flushes (was O(n²)).

Not worthwhile yet: `BlockPos` and OTG noise are mod-internal; no single low-risk
change there.

Final unprofiled cold run at [43400, 43400]:

- 76.27 s, 55.7 c/s, 4,225/4,225
- biomes 256/256, heightmap 256/256, sections 256/256
- 0 CME / AIOOBE / NPE, 0 stall reports, 1 recovered off-thread error
- heap ~4 GB used / 12 GB

## Artifacts (production)

- `dist/worldflow-1.0.2-production-20261023.jar`
- `dist/OpenTerrainGenerator-1.12.2-v9.7-production-20261023.jar`
- `dist/worldflow-otgpatch-production-20261023.jar`
- `dist/SHA256SUMS-production-20261023.txt`

Commit `c827b7d`, tag `milestone-production-wrapup-20261023`.
