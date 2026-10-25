# Contributing to WorldFlow

Thank you for your interest in contributing to **WorldFlow**! We welcome bug reports, performance enhancements, mod compatibility bridges, and pull requests.

---

## 🛠️ Development Setup

WorldFlow is built for **Minecraft 1.12.2** on **Cleanroom** and **Minecraft Forge**, using RetroFuturaGradle.

### Prerequisites
- **JDK 17, 21, or 25**: Required to run the modern Gradle 9+ build daemon.
- **Git**: For version control.
- **IDE**: IntelliJ IDEA (recommended) or Eclipse / VSCode.

### Initializing the Workspace
```bash
# Clone the repository
git clone https://github.com/Brilliafy/WorldFlow.git
cd WorldFlow

# Build the mod
./gradlew build
```

---

## 📐 Architecture & Contribution Guidelines

1. **Deterministic Terrain Math**:
   - Terrain generation must remain bit-identical to vanilla where applicable. Any modifications to `VectorNoiseEngine` or terrain calculations must be audited against canonical seed samples (e.g. using `/worldflow hashchunk`).

2. **Thread Safety & Zero-Garbage in Hot Paths**:
   - World generation runs across multiple worker threads simultaneously. Ensure all state touched by worker threads is thread-local, immutably published, or properly guarded by concurrency locks.
   - Avoid creating excess heap garbage inside block iteration loops and structure placement routines.

3. **Mixin Hygiene**:
   - Keep mixin hooks minimal and surgical.
   - For optional third-party integrations (e.g. OTG, Recurrent Complex, DynamicTrees, Biomes O' Plenty), use `WorldFlowMixinPlugin` to conditionally load mixins only when target mods are active.

4. **Testing & Validation**:
   - Run `./gradlew build` to verify compilation and assert that no synthetic mixin constructor parameters are generated (`verifyNoSyntheticMixinConstructors`).
   - Run in-game benchmark tests (`/worldflow bench 16 8 0 0`) to confirm zero `ConcurrentModificationException` or `NullPointerException` errors.

---

## 🌿 Git Workflow

1. Create a descriptive feature branch:
   ```bash
   git checkout -b feature/your-feature-name
   # or
   git checkout -b fix/issue-description
   ```
2. Follow conventional commit formatting:
   - `feat(pipeline): ...`
   - `fix(staging): ...`
   - `perf(noise): ...`
   - `docs(readme): ...`
3. Never commit directly to `main`.

---

## 🐞 Reporting Issues

When filing issues on our [GitHub Issue Tracker](https://github.com/Brilliafy/WorldFlow/issues):
- Include your Minecraft version, mod loader (Forge or Cleanroom build), and MixinBooter version.
- Provide the full crash log or debug log (`logs/debug.log`) via Pastebin or GitHub Gist.
- Note whether the world is vanilla, Biomes O' Plenty, or OpenTerrainGenerator/Dregora.
