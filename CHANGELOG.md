# WorldFlow Changelog

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
