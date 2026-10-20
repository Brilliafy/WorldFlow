# WorldFlow Changelog

## v1.0.0 — 2026-10-20

### Parallel OTG plotter
- OTG's `CustomStructurePlotter` runs on a 4-thread pool (configurable with
  `-Dworldflow.plotThreads=N`) instead of the single structure thread.
- Per-coordinate striped locks (64 shards) replace the 100x100 region locks:
  plots for the same chunk serialize, everything else runs in parallel.
- `structureNamesPerChunk` scratch is thread-local and bounded (4,096 entries per
  thread); cross-region scans no longer share mutable lists.
- The planner's one-time spawn plot uses an `AtomicBoolean` and runs before the
  caller takes its own coordinate lock, so no two coordinate locks are ever held.
- Shared structure/frequency maps stay under the single cache lock, which is a
  leaf: lock order is spawn-CAS -> one coordinate lock -> cache lock.
- Fixed a mass-fallback bug: `ConcurrentHashMap` rejects null values, and the plot
  fast cache stored `null`; it now stores `Boolean.TRUE`. This alone removed 4,606
  NPE fallbacks in the first fork test.
- `BO4Config.timesSpawned` increments under the cache lock.

### Pipeline correctness
- CARVERS-only neighbour holders now finish atomically (`tryFinishCarverOnly`):
  a FULL request either upgrades the holder before it is removed or creates a new
  holder. This removes the edge-chunk self-heals seen in benchmarks.
- Intentional unloads of neighbour-only holders no longer log a warning with a
  stack trace; real incomplete holders still do.
- OTG `ResourceProcessEvent` dispatch is locked per listener instance, so Charm's
  `VillageDecorations` can no longer throw CMEs while unrelated listeners still run
  in parallel.
- Serialized third-party generators take the world-gen lock again; Recurrent
  Complex and Charm share village structure state across mods.
- Per-mod generator locks are used for the serialized path, but the world-gen lock
  remains the cross-mod ordering point.

### Save correctness
- `awaitQuiesce` now honors the save window as well as the quiesce window; external
  producers (bench/pregen) stay blocked for the whole `WorldServer.saveAll`, not
  just the drain. Previously the window ended before the save and Recurrent
  Complex's `WorldStructureGenerationData` could be mutated during iteration.
- The save drain waits for active/queued base and feature workers, including
  workers whose holders were dropped by `unloadarea`. This removes the
  `MapStorage` ConcurrentModificationException during auto-saves.
- Save window is bounded at 120 s and auto-expires if a save never reports back.

### Memory / lifecycle
- The plotter's fast cache is capped at 65,536 entries.
- `/worldflow unloadarea` discards staged blocks, ticks, entities and tile
  entities for the unloaded area, so repeated pre-generation cycles do not retain
  `StagedBlock` trees.
- `/worldflow bench` reports heap after each area; the soak script samples it.

### Deterministic mode
- `-Dworldflow.deterministic=true` forces one base worker, one feature worker and
  one plot thread. Chunk order is preserved and decoration is bit-identical.
- Default mode is fully parallel. Terrain is bit-identical in both modes;
  decoration order is only guaranteed in deterministic mode.

### Validation
- Radius-32 (4,225 chunks) fresh-area runs: 0 fallbacks, 0 CMEs, 0 crashes,
  0 self-heals.
- Two-hour soak on fresh radius-16 areas with unloadarea: see `state/soak.log`.
- Real Dregora backup: 3,109 `.mca`, 2,639,510 chunks, 0 structural errors.

### Known limitations
- Fast-mode decoration order is not reproducible.
- Dell EC clamp: ~27 chunks/s average on the 65 W Lenovo adapter; genuine Dell
  90 W USB-C removes it.
- The save window waits for in-flight plots (bounded).

## v1.0.0-rc1 — 2026-10-19

### Determinism
- **Terrain is bit-identical across independent runs**: biomes, heightmap and base
  sections matched 81/81 samples at several centres and 12/12 chunks between two
  independently generated worlds with the same seed.
- **Decoration is near-identical, not bit-identical**: 0.2–0.7 % of blocks differ
  between independent runs (random block variants / structure internals). Cause is
  order-dependent stateful generation (OTG structure cache and similar); terrain is
  unaffected. Tracked for a follow-up ordered-execution change.

### Thread safety / correctness
- Serialized Recurrent Complex's `MapGenStructureHook` and OTG's default-structure
  pass. Previously raced and threw ~44,000 NPEs per session, silently producing
  undecorated fallback chunks.
- OTG's `ObjectSpawner` is single-threaded per world (shared `rand`, cascade flags,
  biome cache, BO4 cache). The old 64-way sharded lock only protected the plotter
  against itself; replaced with one global population lock.
- Synchronized `CustomObjectGen.getObjects` lazy list fill (CME).
- Unknown `IWorldGenerator`s now default to `SERIALIZE` (`Serialize Unknown
  Generators=true`).
- World saves quiesce the pipeline and drain for up to 60 s across the whole
  `WorldServer.saveAll` window; fixes `MapStorage` ConcurrentModificationException
  from Recurrent Complex saved data.
- Tile entities are created/removed with proto block writes and staged cross-chunk
  blocks carry a live TE (Roguelike Dungeons loot chests).
- Off-thread base-generation failure retries terrain on the main thread instead of
  publishing a partial, undecorated chunk.
- Fixed orphaned holder futures that stalled benchmarks at 97 % (unconditional
  `inFlight.put` during pending promotion).

### Performance
- Allocation-free worker block reads + neighbourhood cache: feature stage ~16 %
  faster at identical clocks.
- Optional explicit BaseGen/Features split: `/worldflow bench <r> <workers> <x> <z>
  [baseThreads]`.

### Tooling
- `/worldflow hashchunk <x> <z> [terrain]`: canonical SHA-256 over blocks, biomes,
  heightmap and tile entities (terrain mode hashes a fresh `generateChunk`).
- Bench watchdog self-heals orphaned futures and reports stalls sooner.
- `diffarea` forces a 5×5 loaded neighbourhood so reference generation is fair.

### Known limitations
- Dell EC power clamp on unrecognised USB-C adapters: sustained throughput is
  limited to roughly 27 c/s average on the 65 W Lenovo unit. A genuine Dell 90 W
  USB-C adapter (HA90NM190 / 492-BBUU) removes the clamp.
- Decoration reproducibility as described above.
