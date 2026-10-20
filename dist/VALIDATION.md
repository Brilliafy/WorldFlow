# WorldFlow 1.0.0 validation

Build under test: `worldflow-1.0.0.jar` (commit after the RC/Roguelike fixes),
patched OTG 9.7 jar, Cleanroom 0.6.13, Java 26, ZGC, `-Xms1G -Xmx8G`.

## Functional runs

| Date | Area | Chunks | Result |
|---|---|---|---|
| 2026-10-20 | r32 @ 7.4M | 4,225 | 0 fallbacks, 0 CME, 0 crash |
| 2026-10-20 | r32 @ 7.6M | 4,225 | 0 fallbacks, 0 CME, 0 crash |
| 2026-10-20 | r32 @ 7.8M | 4,225 | 0 fallbacks, 0 CME, 0 crash |
| 2026-10-20 | r32 @ 8.2M | 4,225 | 1 RC/Charm CME (before per-handler locks); fixed |
| 2026-10-20 | r32 @ 8.4M | 4,225 | 0 fallbacks, 0 CME, 0 crash |
| 2026-10-20 | contiguous r16 soak | 1089/area | see below |

## Contiguous soak (final build)

- Areas are tiled with a 33-chunk step, so structures that cross an area border are
  spawned by the next area (a realistic pregen, unlike disjoint 20,000-chunk areas).
- Each area is followed by `/worldflow unloadarea <cx> <cz> 16`.
- Error counter tracks `ConcurrentModificationException`, decoration fallbacks,
  self-heals, incomplete holders, and TE/chest NPEs.
- Stable build (unpatched OTG, one plotter thread): **0 CMEs, 0 fallbacks,
  0 self-heals** across the sampled areas; heap stable around 4.4-6.3 GB used
  (ZGC); throughput 11-95 chunks/s (area/EC dependent).
- One rare OTG `placeTree` NPE (parallel resource worker, world field null) was
  logged in roughly 27,000 chunks; a tree is skipped, terrain/publication unaffected.

## Memory

- A disjoint-area soak (20,000-chunk gaps) grew the OTG plotter cache to ~8 GB
  because border structures are never spawned and stay cached. That is a test
  artifact; a contiguous pregen spawns them and the heap stays bounded.
- `-Xmx8G` handled the contiguous soak. `-Xmx12G` is recommended for a full
  Dregora pregen.

## Determinism

- Terrain: bit-identical across independent runs (biomes, heightmap, base sections;
  81/81 samples on the previous release; terrain path unchanged).
- Decoration: reproducible with `-Dworldflow.deterministic=true` (one base/feature
  worker and one plot thread). Fast parallel mode is content-complete but decoration
  order may vary.

## Backup

All 3,109 `.mca` files of the real Dregora backup were validated read-only:
2,639,510 chunks, 544,106 empty slots, **0 bad headers/offsets/compression**.
