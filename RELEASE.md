# WorldFlow 1.0.0 — release notes and install

WorldFlow 1.0.0 is the first release with the parallel OTG structure plotter.

## Files

| File | Purpose |
|---|---|
| `worldflow-1.0.0.jar` | the mod (parallel pipeline + parallel OTG plotter support) |
| `OpenTerrainGenerator-1.12.2-v9.7-original.jar` | untouched OTG 9.7 (use this) |
| `experimental/OpenTerrainGenerator-...-patched.jar` | 4-thread plotter binary patch (experimental) |

## Requirements

- Minecraft 1.12.2
- Cleanroom 0.6.13-alpha (or a Forge build with MixinBooter)
- OpenTerrain Generator 9.7
- A Dregora/OTG world is the intended target; vanilla/Forge worlds also work

## Install

1. Stop the server.
2. Back up the existing `mods/OpenTerrainGenerator-1.12.2-v9.7.jar`.
3. Copy `worldflow-1.0.0.jar` into `mods/`.
4. Use the **unpatched** `OpenTerrainGenerator-1.12.2-v9.7.jar` (or restore the original from this bundle).
5. Start the server.

The first start rebuilds no world data. Existing chunks are never overwritten: the
pipeline checks the disk before generating.

## Rollback

1. Stop the server.
2. Remove `mods/worldflow-1.0.0.jar`.
3. Restore the original OTG jar (`.orig`) as `mods/OpenTerrainGenerator-1.12.2-v9.7.jar`.
4. Start the server.

The world files are compatible in both directions.

## Tuning

| Property | Default | Meaning |
|---|---|---|
| `-Dworldflow.plotThreads=N` | 1 | OTG structure plotter threads (1 is the safe value without the experimental patch) |
| `-Dworldflow.deterministic=true` | off | 1 base worker, 1 feature worker, 1 plot thread; preserves chunk order |

`worldflow.deterministic` makes order-dependent decoration reproducible. It is much
slower; use it for verification worlds, not for production pre-generation.

## Parallel plotter status

The 4-thread `CustomStructurePlotter` binary patch is implemented and passes
functional radius-32 tests, but a long contiguous soak showed the OTG structure
cache growing until the 8 GB heap filled. It is therefore **not part of the stable
install**. The mod defaults to one plotter thread; the patch is provided under
`experimental/` for testing with more memory and future work.

## Validation performed

- **Errors:** repeated radius-32 benchmarks (4,225 chunks each) on fresh areas with
  zero decoration fallbacks, zero ConcurrentModificationExceptions, zero crashes,
  zero self-heals.
- **Long soak:** contiguous radius-16 areas (33-chunk step, 1,089 chunks each) with
  `/worldflow unloadarea` after every area; error counter and heap sampled per area.
  The final build ran 0 errors across the sampled areas with the heap stable around
  6.3-7.3 GB used under `-Xmx8G`.
- **Backup:** every `.mca` in the real Dregora backup validated (3,109 files,
  2,639,510 chunk slots, 544,106 empty slots, **0 bad headers/offsets/compression**).
- **Terrain determinism:** independent generation runs produce identical biomes,
  heightmaps and base sections for the same seed (81/81 samples on the previous
  release; the terrain path is unchanged).
- **Decoration determinism:** bit-identical in `worldflow.deterministic` mode;
  the fast parallel mode is content-complete but decoration order may vary.

## Known limitations

- Decoration order is not reproducible in the fast parallel mode. Terrain is
  always reproducible.
- Stateful third-party mod generators are still serialized per world-gen lock
  (`GeneratorSafetyLevel.SERIALIZE`). OTG resources and the plotter are parallel.
- Sustained throughput on a Dell laptop with a non-Dell USB-C adapter is limited
  by the embedded controller (about 27 chunks/s average at 28 W PL1). A genuine
  Dell 90 W USB-C adapter (HA90NM190 / 492-BBUU / 0H1P9K) removes the clamp.
- A save that overlaps a long structure plot can wait for the plot to finish; the
  save window is bounded and the server continues afterwards.
- 8 GB is enough for a contiguous pregen in testing, but 12 GB is recommended for a
  full Dregora world. Disjoint test areas can look like a leak because border
  structures are never spawned and stay cached.
