package de.bsommerfeld.pathetic.bukkit.provider.world;

import de.bsommerfeld.pathetic.bukkit.ChunkCacheConfiguration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class WorldDomain {

  // Snapshot the active configuration when this world's cache is created (at first use, after
  // PatheticBukkit.initialize has applied any custom configuration).
  private final BoundedChunkCache<DecodedChunk> chunkCache = createCache();

  private static BoundedChunkCache<DecodedChunk> createCache() {
    ChunkCacheConfiguration config = ChunkCacheConfiguration.active();
    return new BoundedChunkCache<>(
        config.maxCachedChunks(),
        config.heatDecayIntervalMs(),
        config.maxHeat(),
        config.memoryPressureEviction(),
        config.minFreeHeapPercent());
  }

  /** Prunes chunks that have cooled to zero heat, letting the cache self-size to its live load. */
  public void sweepCooled() {
    chunkCache.sweepCooled();
  }

  /**
   * Decoded chunk creations currently in progress, keyed by chunk key. Used to ensure that
   * concurrent requests for the same uncached chunk result in a single chunk copy and a single
   * shared {@link DecodedChunk} instead of one per thread.
   */
  private final ConcurrentHashMap<Long, CompletableFuture<DecodedChunk>> inFlightSnapshots =
      new ConcurrentHashMap<>();

  public Optional<DecodedChunk> getDecoded(long key) {
    return Optional.ofNullable(getDecodedOrNull(key));
  }

  /** Allocation-free variant of {@link #getDecoded(long)} for the pathfinding hot path. */
  public DecodedChunk getDecodedOrNull(long key) {
    return chunkCache.get(key);
  }

  public void addDecoded(final long key, final DecodedChunk decoded) {
    chunkCache.put(key, decoded);
  }

  public void removeSnapshot(final long key) {
    chunkCache.remove(key);
  }

  public boolean containsSnapshot(final long key) {
    return chunkCache.containsKey(key);
  }

  /**
   * Registers {@code created} as the in-flight decoded-chunk creation for the given chunk, unless
   * one is already registered.
   *
   * @return the already registered future another thread is completing, or null if {@code created}
   *     was registered and the caller is responsible for completing and {@link
   *     #clearInFlight(long, CompletableFuture) clearing} it.
   */
  public CompletableFuture<DecodedChunk> registerInFlight(
      final long key, final CompletableFuture<DecodedChunk> created) {
    return inFlightSnapshots.putIfAbsent(key, created);
  }

  /** Removes the in-flight registration if it still belongs to {@code created}. */
  public void clearInFlight(final long key, final CompletableFuture<DecodedChunk> created) {
    inFlightSnapshots.remove(key, created);
  }
}
