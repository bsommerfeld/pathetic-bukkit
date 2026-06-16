# Chunk Retrieval Performance — Analysis & Optimizations

**Date:** 2026-06-12
**Status:** Implemented, awaiting benchmark validation (see [Open items](#open-items))

## Problem statement

The Pathetic engine itself is fast (~8 ms for a reference search), but end-to-end searches
through pathetic-bukkit took ~40 ms. Chunk loading was also noticeably slow over longer
distances. The gap lives entirely in the world-access layer:
`FailingNavigationPointProvider.getNavigationPoint` → snapshot cache → `ChunkDataProvider`.

### Why the hot path was expensive

`getNavigationPoint` is called 3–10× per expanded node (e.g. `WalkableProcessor` checks
clearance, ground, step-up, adjacent blocks). With tens of thousands of nodes per search this
means hundreds of thousands of provider calls. Each call used to pay:

1. **Eager `BlockState` creation** — `snapshot.getBlockData(...).createBlockState()` allocates a
   full `CraftBlockState` per call, ~10–50× the cost of the material palette lookup. No processor
   in this repo ever consumed it.
2. **Shared-cache overhead per block access** — two `ConcurrentHashMap` lookups (world UUID →
   `WorldDomain`, then chunk key → entry), one `Long` autoboxing per lookup, a
   `System.currentTimeMillis()` expiry check, a full bulk-cleanup trigger inside
   `ExpiringHashMap.get()`, and two `Optional` allocations.
3. **Repeated coordinate math** — `getFlooredX/Y/Z()` (double floor) evaluated 4–6× per call.

### Why long-distance chunk loading was slow

1. **Serial chunk acquisition** — the search stalls at every chunk border: it discovers the miss,
   blocks until the snapshot is created, continues, and repeats for the next border. Nothing
   overlapped.
2. **Paper: main-thread round trip per miss** — `getChunkAtAsyncUrgently().join()` pays a hop to
   the main thread even for chunks that are already loaded (the common case, since the provider
   checks `isChunkLoaded` first).
3. **No dedup of concurrent snapshot creation** — N parallel searches missing the same chunk
   (e.g. the `/path swarm` benchmark: 500 searches/tick around one player) each produced their own
   full chunk copy.

## Implemented optimizations

### 1. Lazy `BlockState` (`BukkitNavigationPoint`)

The block state is now resolved on first `getBlockState()` access via the stored
`ChunkDataProvider` and in-chunk coordinates. Pathfinding itself only needs
`material.isSolid()`, so the expensive `createBlockState()` no longer runs per node.

> **Breaking change:** the public constructor changed from
> `(ChunkSnapshot, Material, BlockState)` to
> `(ChunkSnapshot, Material, int x, int y, int z, ChunkDataProvider)`. Anyone constructing
> `BukkitNavigationPoint` directly must adapt. `getBlockState()` behaves as before (same value,
> now computed lazily and memoized).

### 2. Thread-local chunk cache (`provider/world/ThreadChunkCache`)

A flat open-addressing table (`long[]` keys, `ChunkSnapshot[]` values, 256 slots) held per
pathfinding thread via `ThreadLocal` in `FailingNavigationPointProvider`. A hit is a primitive
array probe — no boxing, no locks, no clock reads. Only misses fall through to the shared
`SNAPSHOTS_MAP`.

**Invalidation correctness:**

- `FailingNavigationPointProvider.INVALIDATION_GENERATION` (an `AtomicLong`) is bumped by every
  `invalidateChunk` / `invalidateAllChunks` call (fed by `ChunkInvalidateListener`).
- Each thread cache stamps itself with the generation it was filled under; on mismatch it resets
  on the next lookup. One atomic read per lookup is the entire cost.
- Safety net for changes that fire no Bukkit event (direct NMS edits, WorldEdit fast mode):
  entries are not served beyond 30 s (`MAX_AGE_MS`) without re-consulting the shared cache. The
  clock is only read every 1024 lookups to keep this off the hot path.
- Consequence on busy servers: every block change anywhere bumps the generation and resets all
  thread caches. That is intentional (correctness first); the reset costs one 256-slot array fill
  and the next lookups repopulate from the still-valid shared cache.

The cache is bound to one world at a time; alternating worlds on the same executor thread
thrashes it (acceptable — repopulation is cheap).

### 3. In-flight snapshot deduplication (`WorldDomain`)

`WorldDomain` now tracks in-progress snapshot creations in a
`ConcurrentHashMap<Long, CompletableFuture<ChunkSnapshot>>`. In
`FailingNavigationPointProvider.fetchAndCacheSnapshot`, the first thread to miss a chunk
registers a future and creates the snapshot; concurrent threads `join()` that future instead of
copying the chunk again. The registration is always cleared in a `finally` block, and the future
is always completed (with `null` on failure), so waiters cannot hang.

### 4. Background neighbor prefetch (`provider/world/ChunkPrefetcher`)

A demand miss means the search front just entered a new chunk — it will likely cross into an
adjacent one soon. On every demand miss the four axis-neighbor chunks are warmed in the
background so the snapshot copy overlaps with the search working through the current chunk.

Constraints, deliberately chosen:

- **Prefetch never loads or generates chunks.** Only chunks already loaded in the world
  (`world.isChunkLoaded`) are snapshotted. Unloaded terrain is untouched.
- Prefetch-originated fetches do not themselves prefetch (no cascade); dedup via the in-flight
  map prevents double work with demand fetches.
- Executor: 2 daemon threads, created lazily on first use, stopped via
  `ChunkPrefetcher.shutdown()` which `PatheticBukkit.shutdown()` calls. After shutdown the next
  submit transparently restarts it. Everything is best-effort; dropped tasks are harmless.

### 5. Shared-cache hot path slimming

- `containsKey` + `get` double lookups replaced with single `get` /
  `computeIfAbsent` (`FailingNavigationPointProvider`, `invalidateChunk`).
- `WorldDomain.getSnapshotOrNull(long)` added as the allocation-free accessor; the
  `Optional`-returning `getSnapshot` remains as a thin wrapper for compatibility.
- `ExpiringHashMap.get()` no longer triggers the bulk cleanup scan; per-entry expiry semantics
  are unchanged (expired entries are still removed on access), and the bulk sweep still runs from
  mutating operations (`put`, `remove`, …).
- Floored block coordinates are computed once per `getNavigationPoint` call and passed down.
- `ChunkUtil`: the legacy(<1.13) version check is a `static final boolean` instead of being
  re-evaluated per block access.

### 6. Paper provider (`provider/paper/PaperChunkDataProvider`)

- Off-main, already-loaded chunks are fetched via `World#getChunkAtIfLoadedImmediately(int,int)`
  — thread-safe, no main-thread round trip. The method exists on Paper 1.13.2–1.21.x but **was
  removed in Paper API 26.x**, so it is resolved via `MethodHandle` at class init; on 26.x the
  handle is `null` and the code falls back to the previous behavior.
- The fallback now uses `.thenApply(Chunk::getChunkSnapshot).join()` so the snapshot is taken on
  the thread completing the load (main thread) instead of racily on the joining pathfinding
  thread.

## Files touched

| File | Change |
| --- | --- |
| `core/.../provider/BukkitNavigationPoint.java` | Lazy block state, coordinate-based constructor |
| `core/.../provider/FailingNavigationPointProvider.java` | Thread cache, generation counter, dedup, prefetch wiring, slimmed lookup path |
| `core/.../provider/LoadingNavigationPointProvider.java` | Uses shared `createNavigationPoint` |
| `core/.../provider/world/ThreadChunkCache.java` | **New** — per-thread snapshot cache |
| `core/.../provider/world/ChunkPrefetcher.java` | **New** — lazy daemon prefetch executor |
| `core/.../provider/world/WorldDomain.java` | `getSnapshotOrNull`, in-flight registry |
| `core/.../provider/world/ExpiringHashMap.java` | No bulk cleanup from `get()` |
| `core/.../util/ChunkUtil.java` | Hoisted legacy check |
| `core/.../PatheticBukkit.java` | `shutdown()` stops the prefetcher |
| `provider/paper/.../PaperChunkDataProvider.java` | Reflective immediate path, safe snapshot thread |

Build: full `./mvnw install` green, `ExpiringHashMapTest` 9/9 passing.

## Open items

### Benchmarks (next step, pending)

Run before/after on a real server:

1. `/path swarm` — µs/path should drop sharply (thread-cache hits + dedup).
2. Single long-distance `/path start` over **loaded** terrain — cold run (prefetch + dedup
   effect) vs. immediately repeated warm run (thread-cache effect). The warm run is the number to
   compare against the ~8 ms engine-only baseline.
3. Watch GC pressure / allocation rate if profiling: the per-call `Optional`/`Long`/lambda/
   `CraftBlockState` allocations are gone; remaining per-call allocation is one
   `BukkitNavigationPoint`.

If the warm long-distance run is still far above the engine baseline, profile
`ChunkSnapshot.getBlockType` — it performs a registry lookup (`minecraftToBukkit`) per call. The
escalation path is an NMS provider that reads palettes directly and decodes materials once per
chunk into a flat array, skipping `ChunkSnapshot` entirely.

### PreloadingHook (agreed direction, needs Pathetic API change)

Unloaded terrain is still demand-loaded serially by design (prefetch refuses to trigger chunk
loading/generation). The clean fix is an engine-side hook that fires **once at path start with
start and target positions**, letting pathetic-bukkit preload the chunk corridor between them in
parallel (Paper: `getChunkAtAsync` batch; Spigot: prefetch executor) before/while the search
runs.

Today's `PathfinderHook` only exposes `onPathfindingStep(PathfindingContext)` with
`currentPosition` + `depth` — no start/target, so this cannot be built in pathetic-bukkit alone.
Sketch:

```java
// pathetic API (engine repo)
public interface PathfinderHook {
  default void onPathfindingStart(PathfindingStartContext context) {} // start, target, environment
  void onPathfindingStep(PathfindingContext pathfindingContext);
}

// pathetic-bukkit: PreloadingHook implements onPathfindingStart
//  - walk the chunk line start→target (plus 1-chunk margin)
//  - submit corridor chunks to ChunkPrefetcher / getChunkAtAsync
//  - never block the search; demand path stays the fallback
```

Open questions: corridor width (straight line vs. margin for A* wandering), cap on preloaded
chunks per search (worldgen cost!), and whether `LoadingNavigationPointProvider` semantics
("load whatever is needed") should gate it.

### Known limitations / accepted trade-offs

- Thread caches reset on *any* chunk invalidation in *any* world (single global generation).
  Fine-grained per-world generations were skipped for simplicity; revisit only if profiling shows
  reset churn.
- A snapshot invalidated *while* its creation is in flight may be cached briefly stale until the
  5-minute TTL; this race predates these changes (snapshot creation was never atomic with the
  cache write).
- On failure the in-flight future completes with `null` and waiters receive `null` (no retry per
  waiter, unlike before). Misses surface as missing navigation points, same as an unloaded chunk.
- Paper 26.x has no API to fetch a loaded chunk off-main without the main-thread hop; candidates:
  Paper feature request, or an NMS-based path in the paper provider.
