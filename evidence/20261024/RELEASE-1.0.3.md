# WorldFlow 1.0.3 - release notes (2026-10-24)

## Artifacts

| file | sha256 |
|---|---|
| `worldflow-1.0.3-release.jar` | `7bbda9689cec6baaf75c255450c17f8ab48c0e1b165d1fdf42e0a76da40bfe90` |
| `OpenTerrainGenerator-1.12.2-v9.7-release.jar` | `39b31cb77734fa7e54f875a6f5893c96dc93313ad8aea4c0f290a7d2ad190df4` |
| `worldflow-otgpatch-release.jar` | `b59a6bae7a32c49476ee67b473a64d94478a6f386f61a882d1b3f9589612ed32` |

Install: replace the three jars in `mods/`. Delete older `worldflow-*.jar` first
(two versions of the same mod id will crash the game).

- `worldflow-otgpatch-release.jar` is the patch source jar; its classes are already
  merged into `OpenTerrainGenerator-1.12.2-v9.7-release.jar`. Do not install both
  OTG jars.
- Bytecode target: Java 8 (class file 52) for every class in both mod jars.
  Runs on Forge 1.12.2 / Java 8 and Cleanroom / Java 25.

## Defaults

- Parallel pipeline: unchanged (`Concurrency/Enable Parallel Generation`).
- `Concurrency/Enable Biome Prefetcher` = `true` (new in 1.0.3).
- NoiseThreader: if present, `multithreadBetterCavesNoise` is forced to `false` in
  memory at startup. No config edit needed. Scalar BetterCaves optimizations stay on.

## Final smoke test

Cold world, 4,225 chunks, center [43700, 43700], 12 GB heap:

- 80.61 s, 52.7 c/s
- 256/256 biomes, 256/256 heightmap, 256/256 sections
- 0 CME / AIOOBE / NPE, 0 off-thread errors, 0 stall reports
- heap 5.2 GB used / 12 GB

## Fixes in 1.0.3

- batched OTG biome pre-primer (2 daemon threads, 96 chunks ahead, bit-identical).
- per-`Chunk` write lock shared by every `ProtoChunk` wrapper (palette race).
- DynamicTrees `PoissonDiscProviderUniversal.getProvider` serialized: concurrent
  provider construction raced Forge's `ListenerList` child list.
- phase-dep gate advisory (10 s) and released on the existing-chunk publish path.
- OTG `TreeGen` eager load, OTG biome layer thread confinement, canonical
  `ChunkCoordinate`, Charm/RecurrentComplex thread safety (from 1.0.2 line).

## Known limits

- CPU quota on the test instance is 17.28 cores; measured runs saturate it
  (16.6-17.2 cores, heavy throttling). Throughput on a 32-core allocation should
  scale accordingly.
- GPU acceleration was evaluated and rejected: Stage-1 math is ~11% of chunk time
  (Amdahl ~1.12x), and the OTG layer chain would need a bit-exact 20-layer port.
  No native/OpenCL code ships.
