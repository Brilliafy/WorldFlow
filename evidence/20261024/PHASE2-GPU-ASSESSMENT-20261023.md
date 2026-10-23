# Phase 2: GPU / SIMD pre-primer assessment (2026-10-23)

Branch: `feature/gpu-wavefront-accel` (commit `3625e2f`, not merged)

## Hardware vs Java binding

The Vast instance does have a GPU:

- NVIDIA Quadro P4000, 8 GB, compute capability 6.1
- driver 535.288.01, CUDA 12.2, `libnvidia-opencl.so.1` present

But the 1.12.2 / Cleanroom classpath has no OpenCL binding:
`org.lwjgl.opencl` is absent (ClassNotFoundException), and the mod does not
bundle JOCL/JNA. So an OpenCL kernel cannot be launched without adding a native
binding (JOCL 2.x is Java 8-compatible and would be the smallest addition).

## What was built

1. `OpenClProbe` - reflective, Java 8 bytecode, optional; logs one line and
   falls back to the CPU wavefront when no runtime/binding is found.
2. `BiomePrefetcher` - batched, bit-identical CPU pre-primer, opt-in via
   `-Dworldflow.prefetchBiomes=true`. Two low-priority daemon threads warm the
   per-chunk OTG biome cache 96 chunks ahead of the dispatch point. It calls the
   exact same `OTGParallelContext.getChunkOTGBiomes` path used by the feature
   stage, so the 256/256 gate is unaffected.

## Measured A/B (same center [43500, 43500], cold world each time)

| configuration | time | rate | gate |
|---|---|---|---|
| baseline (no pre-primer) | 91.91 s | 46.5 c/s | 256/256 |
| pre-primer (2 threads) | 81.95 s | 52.1 c/s | 256/256 |

Net: -9.96 s, **+10.8%**. Zero CME, zero stall reports.

Run-to-run variance at this center is high (other centers: 52.8 s, 63.2 s,
69.0 s, 76.3 s, 80.3 s), so treat 10.8% as directional, not a spec.

## Why a GPU kernel is not a good next step

Measured per-chunk split (unprofiled): BaseGen ~90-100 ms, Features ~200-230 ms.
Profiler leaves:

- OTG biome layers (Layer.initChunkSeed, LayerBiomeInBiome, getBiomeFromLayer,
  LayerFromImage): ~7-8% of CPU
- OTG Perlin noise (grad / populateNoiseArray3D): ~3-4%
- Top feature leaf is OTG BO3 spawning (`HashMap.putVal`/`HashSet.add`), not
  data-parallel math.

Offloading 100% of biome+noise gives an Amdahl ceiling of ~1.12-1.14x, before
PCIe copies and synchronisation. The P4000's fp64 throughput is also weak, and
the OTG layer chain is ~20 dependent layers of custom 64-bit LCG state that
would each need a bit-exact OpenCL port to keep the 256/256 gate. That is a
multi-day port for a low-single-digit ceiling on this workload.

The actual wins found were on the CPU: canonical `ChunkCoordinate` removed 60%
of allocations, and the published-chunk write staging removed the palette race.

## Recommendation

- Keep the CPU wavefront as the production path.
- Keep `BiomePrefetcher` as an opt-in latency aid (already committed on the
  branch).
- If GPU work is still wanted, the only sane first milestone is:
  bundle JOCL, port `NoiseGeneratorPerlin` only, and prove bit-identical
  `double[]` output against Java for 1,024 chunks. Do not touch the layer chain
  until that passes and shows a net win after copies.
