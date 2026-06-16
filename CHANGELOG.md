## Changelog

### Added

- New `world-reader` module: a portable, dependency-free reader for a world's chunk data straight from its Anvil region
  files on disk. Shaded into `core`, so consumers gain it automatically.
- Disk fast path in `LoadingNavigationPointProvider`: chunks that are generated but not currently loaded are read from
  the world's region files off-thread instead of round-tripping through the server, cutting long-distance cold-start
  time by an order of magnitude.
- `PreloadingHook`: preloads the chunk corridor between start and target the moment a search begins, using the engine's
  `PathfinderHook.onPathfindingStart` (Pathetic 5.5.2). The corridor is warmed in parallel in the background while the
  search runs. Short paths (start and target within a few chunks) skip preloading entirely, so the frequent short
  searches of entity AI don't pay a corridor's worth of snapshot copies.
- Background neighbour-chunk prefetching: a demand miss warms the four axis-adjacent loaded chunks while the search
  works through the current one.
- `ChunkCacheConfiguration` and `PatheticBukkit.initialize(plugin, config)`: the chunk cache's heat-decay interval (in
  any time unit, e.g. seconds), max heat, background-sweep interval and hard size ceiling are now configurable. The size
  ceiling defaults to a heap-scaled value, so it is no longer a one-size-fits-all constant. An optional memory-pressure
  mode (off by default) additionally sheds cold chunks when free heap drops below a configurable percentage, so the
  cache uses only the memory that is actually free and cannot push the server toward OOM. A
  `prefetchExecutor` may also be supplied to run background prefetch on your own pool (separate from
  the search executor) instead of Pathetic's; Pathetic never shuts a supplied executor down.

### Changed

- Per-block material lookups are decoded once per chunk and reused (`DecodedChunk`), removing the repeated block-type
  registry lookup that dominated the per-node hot path.
- Navigation points are memoized per position per pathfinding thread, collapsing the validation/cost processors'
  redundant ground, clearance and side re-samples of the same block.
- `BukkitNavigationPoint` resolves its `BlockState` lazily; pathfinding no longer builds one per node. **Breaking:** the
  public constructor now takes the decoded chunk, in-chunk coordinates and a `ChunkDataProvider` instead of a
  `ChunkSnapshot` and a pre-built `BlockState`. Its `getChunk()` returns `null` for points read from disk (which carry
  no snapshot).
- `PatheticBukkit.initialize()` warms up the version-specific provider resolution, the prefetch executor and the
  disk-reader classes, so the first search no longer pays that one-time setup on its critical path.
- `BStatsUtil` records pathfinding steps with a `LongAdder` (far less contention when many searches report at once), and
  `getPathfindingSteps()` now returns the steps since the last read and resets, matching the bStats per-interval chart
  instead of growing without bound.
- The shared chunk cache is now heat-based per world: every access warms a chunk and idle time cools it, so hotspots
  (spawn, farms, common routes) stay resident while one-off terrain falls away. Because heat resets on access, a
  continuously-used chunk never expires on a fixed timer — only genuinely idle ones do. A background sweep drops cooled
  chunks each minute so the cache self-sizes to its live working set, and its size limit is a heap-scaled
  out-of-memory backstop rather than a working cap — it neither throttles a large server nor risks OOM on a small one.
- A single-block change now patches just that block in the cached chunk instead of dropping and re-fetching the whole
  chunk, and invalidates only the cached navigation points at the changed block's coordinates (a per-chunk modification
  count plus per-block change stamps) rather than resetting every search's caches — so an edit anywhere no longer forces
  unrelated searches, or even other blocks in the same chunk, into a cold start. Multi-block events (pistons,
  explosions, fluid flow) still invalidate the whole chunk.
- Thread-local chunk caching and in-flight snapshot de-duplication remove per-call map overhead and stop concurrent
  searches from copying the same chunk more than once.
- The Paper provider reads already-loaded chunks off the main thread where the API allows, avoiding a main-thread round
  trip per chunk.
- Long-distance searches preload their full straight-line route (not just the start) in parallel, so a long path's disk
  reads run ahead of the search front instead of serially. Combined with the self-sizing cache above, a repeated long
  path no longer thrashes the cache and roughly doubles in time.

### Fixed

- `PatheticBukkit.shutdown()` now clears the static chunk cache; cached world data no longer survives a plugin
  disable/re-enable.
- `BukkitNavigationPoint.getBlockState()` works for chunks read from disk too, reconstructing the block data from the
  region-file palette, so processors retrieve block data identically whether a chunk is loaded or read off-disk.

### Removed

- `ExpiringHashMap` (the internal time-based chunk-cache map), superseded by the bounded, frequency-aware chunk cache.
