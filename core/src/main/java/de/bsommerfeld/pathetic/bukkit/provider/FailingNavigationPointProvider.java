package de.bsommerfeld.pathetic.bukkit.provider;

import de.bsommerfeld.pathetic.api.pathing.context.EnvironmentContext;
import de.bsommerfeld.pathetic.api.provider.NavigationPoint;
import de.bsommerfeld.pathetic.api.provider.NavigationPointProvider;
import de.bsommerfeld.pathetic.api.wrapper.PathPosition;
import de.bsommerfeld.pathetic.bukkit.context.BukkitEnvironmentContext;
import de.bsommerfeld.pathetic.bukkit.provider.anvil.AnvilChunkLoader;
import de.bsommerfeld.pathetic.bukkit.provider.anvil.ChunkBlockSource;
import de.bsommerfeld.pathetic.bukkit.provider.world.ChunkPrefetcher;
import de.bsommerfeld.pathetic.bukkit.provider.world.DecodedChunk;
import de.bsommerfeld.pathetic.bukkit.provider.world.SnapshotBlockSource;
import de.bsommerfeld.pathetic.bukkit.provider.world.ThreadChunkCache;
import de.bsommerfeld.pathetic.bukkit.provider.world.ThreadPositionCache;
import de.bsommerfeld.pathetic.bukkit.provider.world.WorldDomain;
import de.bsommerfeld.pathetic.bukkit.util.BukkitVersionUtil;
import de.bsommerfeld.pathetic.bukkit.util.ChunkUtil;
import de.bsommerfeld.pathetic.resolver.ChunkDataProviderResolver;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/**
 * The {@code FailingNavigationPointProvider} class implements the {@link NavigationPointProvider}
 * interface and provides a default implementation for retrieving navigation point data from a
 * Minecraft world. It uses chunk snapshots to efficiently access information, even in asynchronous
 * contexts.
 *
 * <p>This provider also uses NMS (net.minecraft.server) utilities to bypass the Spigot AsyncCatcher
 * and fetch snapshots natively from an asynchronous context. This allows for more flexible and
 * efficient access to world data.
 *
 * <p>Note: While this provider is designed to efficiently retrieve navigation point data, it may
 * encounter failures or null results if the pathfinder is not permitted to load chunks or if chunks
 * are not loaded in the world. Developers using this provider should handle potential failures
 * gracefully.
 */
public class FailingNavigationPointProvider implements NavigationPointProvider {

  /**
   * A map storing chunk snapshots for each world, indexed by the world's UUID. This allows for
   * efficient retrieval of chunk data without repeatedly accessing the world.
   */
  protected static final Map<UUID, WorldDomain> SNAPSHOTS_MAP = new ConcurrentHashMap<>();

  /**
   * A resolver responsible for providing access to chunk data based on the server's version. This
   * ensures compatibility across different Minecraft versions.
   */
  protected static final ChunkDataProviderResolver CHUNK_DATA_PROVIDER_RESOLVER;

  /**
   * Bumped on every chunk invalidation. Thread-local caches stamp themselves with the generation
   * they were filled under and self-reset when it changes, so they never serve snapshots that
   * survived an invalidation.
   */
  private static final AtomicLong INVALIDATION_GENERATION = new AtomicLong();

  /**
   * Per-thread snapshot cache fronting {@link #SNAPSHOTS_MAP}, so the per-block hot path avoids
   * concurrent map lookups and boxing entirely.
   */
  private static final ThreadLocal<ThreadChunkCache> THREAD_CHUNK_CACHE =
      ThreadLocal.withInitial(ThreadChunkCache::new);

  /**
   * Per-thread navigation-point cache fronting {@link #THREAD_CHUNK_CACHE}, keyed by packed block
   * position. Validation and cost processors sample the same block several times per node and
   * across neighbouring nodes; this collapses those repeats into a single chunk lookup, material
   * decode and allocation.
   */
  private static final ThreadLocal<ThreadPositionCache> THREAD_POSITION_CACHE =
      ThreadLocal.withInitial(ThreadPositionCache::new);

  /** Offsets of the four axis-adjacent chunks considered for snapshot prefetching. */
  private static final int[][] NEIGHBOR_CHUNK_OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

  static {
    if (BukkitVersionUtil.isLegacyVersion()) {
      BukkitVersionUtil.Version version = BukkitVersionUtil.getVersion();
      CHUNK_DATA_PROVIDER_RESOLVER = new ChunkDataProviderResolver(version.getMajor(), version.getMinor());
    } else {
      BukkitVersionUtil.CalendarVersion version = BukkitVersionUtil.getCalendarVersion();
      CHUNK_DATA_PROVIDER_RESOLVER = new ChunkDataProviderResolver(version.getYear(), version.getFeature(), version.getPatch());
    }
  }

  /**
   * Invalidates the cached chunk snapshot for the specified world and chunk coordinates. This
   * method should be called when a chunk is modified to ensure that the provider uses the updated
   * chunk data.
   *
   * @param worldUUID The UUID of the world.
   * @param chunkX The X coordinate of the chunk.
   * @param chunkZ The Z coordinate of the chunk.
   */
  public static void invalidateChunk(UUID worldUUID, int chunkX, int chunkZ) {
    WorldDomain worldDomain = SNAPSHOTS_MAP.get(worldUUID);
    if (worldDomain != null) {
      worldDomain.removeSnapshot(ChunkUtil.getChunkKey(chunkX, chunkZ));
    }
    INVALIDATION_GENERATION.incrementAndGet();
  }

  /**
   * Applies a single block change to the cache instead of dropping the whole chunk. If the chunk is
   * cached, the one block is patched in place (cheap), keeping the expensive snapshot/disk data; if
   * it is not cached there is nothing to patch. Either way the invalidation generation is bumped so
   * the per-thread position caches, which hold navigation points with the old material, reset.
   *
   * @param newBlockData the block's state <em>after</em> the change (callers read it from the event).
   */
  public static void invalidateBlock(
      UUID worldUUID, int blockX, int blockY, int blockZ, BlockData newBlockData) {
    WorldDomain worldDomain = SNAPSHOTS_MAP.get(worldUUID);
    DecodedChunk decoded =
        worldDomain == null
            ? null
            : worldDomain.getDecodedOrNull(ChunkUtil.getChunkKey(blockX >> 4, blockZ >> 4));

    if (decoded != null) {
      // Granular: patch the block and bump only this chunk's modification count. Navigation points
      // for this chunk recompute (they detect the bumped count via isStale); every other chunk's
      // points and all thread caches are left untouched — so a block change no longer resets every
      // search's caches, which is what made unrelated searches pay a cold start after any edit.
      decoded.applyBlockChange(blockX & 0xF, blockY, blockZ & 0xF, newBlockData);
    } else {
      // Not cached: there is nothing to patch, but stale points may linger in position caches for a
      // chunk that has since been evicted, so fall back to the global reset.
      INVALIDATION_GENERATION.incrementAndGet();
    }
  }

  /**
   * Invalidates all cached chunk snapshots for the specified world. This method should be called
   * when a world is unloaded or when significant changes occur that affect the entire world.
   *
   * @param worldUUID The UUID of the world.
   */
  public static void invalidateAllChunks(UUID worldUUID) {
    SNAPSHOTS_MAP.remove(worldUUID);
    INVALIDATION_GENERATION.incrementAndGet();
  }

  /**
   * Drops every cached chunk for every world. The cache is static, so without this it would survive
   * a plugin disable/re-enable and retain stale chunk data (and memory) across the reload. Called
   * from {@code PatheticBukkit.shutdown()}; the generation bump also resets the per-thread caches.
   */
  public static void clearAll() {
    SNAPSHOTS_MAP.clear();
    INVALIDATION_GENERATION.incrementAndGet();
  }

  /**
   * Prunes cooled-to-zero chunks from every world's cache, letting the heat-based caches self-size
   * down to their live working set. Driven periodically by {@code CacheSweeper}.
   */
  public static void sweepCaches() {
    for (WorldDomain worldDomain : SNAPSHOTS_MAP.values()) {
      worldDomain.sweepCooled();
    }
  }

  /**
   * Fetches the navigation point data at the given block coordinates. This method retrieves the
   * decoded chunk containing the position and extracts the relevant information, such as the
   * material. The block state is resolved lazily on first access since creating it is significantly
   * more expensive than the material lookup.
   *
   * @return The navigation point, or null if the chunk is not loaded or the position is invalid.
   */
  private static NavigationPoint fetchNavigationPoint(
      BukkitEnvironmentContext environmentContext, int blockX, int blockY, int blockZ) {
    DecodedChunk decodedChunk =
        resolveDecodedChunk(blockX >> 4, blockZ >> 4, environmentContext);
    if (decodedChunk == null) return null;

    return createNavigationPoint(decodedChunk, blockX & 0xF, blockY, blockZ & 0xF);
  }

  /**
   * Creates a navigation point whose material comes from the shared per-chunk decode cache (so the
   * registry lookup behind {@code getBlockType} is paid at most once per distinct block) and whose
   * block state is resolved lazily on first access.
   */
  static NavigationPoint createNavigationPoint(DecodedChunk decodedChunk, int x, int y, int z) {
    Material material = decodedChunk.getMaterial(x, y, z);
    return new BukkitNavigationPoint(
        decodedChunk, material, x, y, z, CHUNK_DATA_PROVIDER_RESOLVER.getChunkDataProvider());
  }

  /**
   * Retrieves the chunk snapshot containing the given position. This method first checks if a
   * snapshot is already cached for the world. If not, it attempts to load the chunk and create a
   * new snapshot.
   *
   * @param position The position used to determine the chunk coordinates.
   * @return An optional containing the chunk snapshot if found, or an empty optional if the chunk
   *     is not loaded or the world is invalid.
   */
  protected static Optional<ChunkSnapshot> getChunkSnapshot(
      PathPosition position, BukkitEnvironmentContext environmentContext) {
    DecodedChunk decodedChunk =
        resolveDecodedChunk(
            position.getFlooredX() >> 4, position.getFlooredZ() >> 4, environmentContext);
    return decodedChunk == null ? Optional.empty() : Optional.of(decodedChunk.getSnapshot());
  }

  /**
   * Allocation-free resolution of the decoded chunk for a chunk coordinate; called once per
   * navigation point miss. Hits are served from a thread-local cache; only misses touch the shared
   * snapshot cache.
   */
  private static DecodedChunk resolveDecodedChunk(
      int chunkX, int chunkZ, BukkitEnvironmentContext environmentContext) {
    long chunkKey = ChunkUtil.getChunkKey(chunkX, chunkZ);
    UUID uuid = environmentContext.getWorld().getUID();
    long generation = INVALIDATION_GENERATION.get();

    ThreadChunkCache threadCache = THREAD_CHUNK_CACHE.get();
    DecodedChunk decoded = threadCache.lookup(uuid, generation, chunkKey);
    if (decoded != null) return decoded;

    decoded = resolveSharedDecodedChunk(uuid, chunkKey, chunkX, chunkZ);
    if (decoded != null) threadCache.store(chunkKey, decoded);
    return decoded;
  }

  private static DecodedChunk resolveSharedDecodedChunk(
      UUID uuid, long chunkKey, int chunkX, int chunkZ) {
    WorldDomain worldDomain = SNAPSHOTS_MAP.computeIfAbsent(uuid, unused -> new WorldDomain());
    DecodedChunk cached = worldDomain.getDecodedOrNull(chunkKey);
    if (cached != null) return cached;

    World world = Bukkit.getWorld(uuid);
    if (world == null || !world.isChunkLoaded(chunkX, chunkZ)) return null;

    return fetchAndCacheSnapshot(worldDomain, world, chunkKey, chunkX, chunkZ, true);
  }

  /**
   * Preloads and caches the decoded chunk at the given coordinates, unless it is already cached.
   * Loaded chunks are snapshotted live; unloaded chunks are read from disk (the region files) in
   * parallel ahead of the search front. It deliberately does <em>not</em> generate missing terrain —
   * speculatively world-generating a guessed corridor is expensive and often wrong; the demand path
   * generates only the chunks the search actually reaches. Must run on a background thread (see
   * {@link ChunkPrefetcher}).
   */
  public static void preloadChunk(World world, int chunkX, int chunkZ) {
    WorldDomain worldDomain =
        SNAPSHOTS_MAP.computeIfAbsent(world.getUID(), unused -> new WorldDomain());
    long chunkKey = ChunkUtil.getChunkKey(chunkX, chunkZ);
    if (worldDomain.containsSnapshot(chunkKey)) return;

    if (world.isChunkLoaded(chunkX, chunkZ)) {
      fetchAndCacheSnapshot(worldDomain, world, chunkKey, chunkX, chunkZ, false);
    } else {
      createDeduplicated(worldDomain, chunkKey, () -> AnvilChunkLoader.load(world, chunkX, chunkZ));
    }
  }

  /**
   * Eagerly performs the one-time, deterministic setup the first search would otherwise pay on its
   * critical path: resolving the version-specific chunk-data provider (reflection), starting the
   * prefetch executor, and loading the disk-reader classes. Called from {@code
   * PatheticBukkit.initialize}. (JIT warmup of the search itself still needs a real search.)
   */
  public static void prewarm() {
    CHUNK_DATA_PROVIDER_RESOLVER.getChunkDataProvider();
    ChunkPrefetcher.ensureStarted();
    AnvilChunkLoader.prewarm();
  }

  /**
   * Creates and caches the decoded chunk for an uncached chunk from a live server snapshot.
   * Concurrent requests for the same chunk are deduplicated (see {@link #createDeduplicated}).
   */
  private static DecodedChunk fetchAndCacheSnapshot(
      WorldDomain worldDomain,
      World world,
      long chunkKey,
      int chunkX,
      int chunkZ,
      boolean prefetchNeighbors) {
    DecodedChunk decoded =
        createDeduplicated(
            worldDomain,
            chunkKey,
            () -> {
              ChunkSnapshot snapshot =
                  CHUNK_DATA_PROVIDER_RESOLVER
                      .getChunkDataProvider()
                      .getSnapshot(world, chunkX, chunkZ);
              return snapshot == null ? null : new SnapshotBlockSource(snapshot);
            });
    if (decoded != null && prefetchNeighbors) {
      prefetchNeighborSnapshots(worldDomain, world, chunkX, chunkZ);
    }
    return decoded;
  }

  /**
   * Creates and caches a decoded chunk from {@code sourceSupplier}, deduplicating concurrent
   * requests for the same chunk: the first caller runs the supplier (which may copy a snapshot or
   * read the chunk from disk), everyone else waits for and shares that single {@link DecodedChunk}
   * instead of producing one per thread. The supplier may return {@code null} to signal "no data",
   * in which case nothing is cached and waiters receive {@code null} too.
   */
  protected static DecodedChunk createDeduplicated(
      WorldDomain worldDomain, long chunkKey, Supplier<ChunkBlockSource> sourceSupplier) {
    CompletableFuture<DecodedChunk> created = new CompletableFuture<>();
    CompletableFuture<DecodedChunk> inFlight = worldDomain.registerInFlight(chunkKey, created);
    if (inFlight != null) {
      return inFlight.join();
    }

    DecodedChunk decoded = null;
    try {
      ChunkBlockSource source = sourceSupplier.get();
      if (source != null) {
        decoded = new DecodedChunk(source);
        worldDomain.addDecoded(chunkKey, decoded);
      }
      return decoded;
    } finally {
      created.complete(decoded);
      worldDomain.clearInFlight(chunkKey, created);
    }
  }

  /**
   * A demand miss means the search front just entered a new chunk, so it will likely cross into
   * an adjacent one soon. Warm the axis neighbors in the background while the search is busy with
   * the current chunk — but only those already loaded in the world, so prefetching never triggers
   * chunk loading or generation.
   */
  private static void prefetchNeighborSnapshots(
      WorldDomain worldDomain, World world, int chunkX, int chunkZ) {
    for (int[] offset : NEIGHBOR_CHUNK_OFFSETS) {
      int neighborX = chunkX + offset[0];
      int neighborZ = chunkZ + offset[1];
      long neighborKey = ChunkUtil.getChunkKey(neighborX, neighborZ);
      if (worldDomain.containsSnapshot(neighborKey)) continue;

      ChunkPrefetcher.submit(
          () -> {
            if (worldDomain.containsSnapshot(neighborKey)) return;
            if (!world.isChunkLoaded(neighborX, neighborZ)) return;
            fetchAndCacheSnapshot(worldDomain, world, neighborKey, neighborX, neighborZ, false);
          });
    }
  }

  /**
   * Processes a chunk snapshot by wrapping it in a {@link DecodedChunk} and caching it in the
   * {@link #SNAPSHOTS_MAP}. This ensures that future requests for the same chunk can be served from
   * the cache, sharing the decode cache.
   *
   * @param chunkX The X coordinate of the chunk.
   * @param chunkZ The Z coordinate of the chunk.
   * @param chunkSnapshot The chunk snapshot to process.
   * @return The cached decoded chunk, or null if {@code chunkSnapshot} was null.
   */
  protected static DecodedChunk processChunkSnapshot(
      BukkitEnvironmentContext environmentContext,
      int chunkX,
      int chunkZ,
      ChunkSnapshot chunkSnapshot) {
    if (chunkSnapshot == null) return null;
    UUID uuid = environmentContext.getWorld().getUID();
    WorldDomain worldDomain = SNAPSHOTS_MAP.computeIfAbsent(uuid, unused -> new WorldDomain());
    DecodedChunk decoded = DecodedChunk.ofSnapshot(chunkSnapshot);
    worldDomain.addDecoded(ChunkUtil.getChunkKey(chunkX, chunkZ), decoded);
    return decoded;
  }

  @Override
  public final NavigationPoint getNavigationPoint(
      PathPosition pathPosition, EnvironmentContext environmentContext) {
    BukkitEnvironmentContext bukkitEnvironmentContext =
        (BukkitEnvironmentContext) environmentContext;

    int blockX = pathPosition.getFlooredX();
    int blockY = pathPosition.getFlooredY();
    int blockZ = pathPosition.getFlooredZ();

    long positionKey = packPosition(blockX, blockY, blockZ);
    UUID uuid = bukkitEnvironmentContext.getWorld().getUID();
    long generation = INVALIDATION_GENERATION.get();

    ThreadPositionCache positionCache = THREAD_POSITION_CACHE.get();
    NavigationPoint cached = positionCache.lookup(uuid, generation, positionKey);
    // A cached point is reused unless a block in its chunk changed since it was built (a granular,
    // per-chunk check that avoids resetting the whole cache on every block edit).
    if (cached != null && !((BukkitNavigationPoint) cached).isStale()) return cached;

    NavigationPoint point =
        computeNavigationPoint(bukkitEnvironmentContext, blockX, blockY, blockZ);
    if (point != null) positionCache.store(positionKey, point);
    return point;
  }

  /**
   * Produces the navigation point for a block position on a cache miss. The {@code Failing}
   * variant returns null when the chunk is not loaded; {@code LoadingNavigationPointProvider}
   * overrides this to load the chunk instead.
   */
  protected NavigationPoint computeNavigationPoint(
      BukkitEnvironmentContext environmentContext, int blockX, int blockY, int blockZ) {
    return fetchNavigationPoint(environmentContext, blockX, blockY, blockZ);
  }

  /**
   * Packs a block position into a long key for the position cache: 26 bits each for x and z (more
   * than the +/-30M world border needs) and 12 bits for y. Two positions only collide if all three
   * components are congruent modulo those widths, which cannot happen within a single search's
   * spatial extent.
   */
  private static long packPosition(int x, int y, int z) {
    return (x & 0x3FFFFFFL) | ((long) (z & 0x3FFFFFF) << 26) | ((long) (y & 0xFFF) << 52);
  }
}
