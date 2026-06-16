package de.bsommerfeld.pathetic.bukkit.provider.world;

import java.util.Arrays;
import java.util.UUID;

/**
 * A small {@link DecodedChunk} cache held once per pathfinding thread. It exists purely to keep the
 * per-block hot path of navigation point lookups off the shared {@code ConcurrentHashMap}s: a
 * lookup here is a primitive-key probe into a flat array — no boxing, no locks, no clock reads.
 *
 * <p>The cache is bound to a (world, invalidation generation) pair. Whenever a chunk is
 * invalidated anywhere, the global generation is bumped and every thread cache resets itself on
 * its next lookup, so invalidation semantics match the shared cache. As a safety net for changes
 * that produce no Bukkit event (e.g. direct NMS edits), entries are additionally not served
 * beyond {@link #MAX_AGE_MS} without re-consulting the shared cache.
 *
 * <p>Not thread-safe by design; instances must be thread-confined (see usage via {@code
 * ThreadLocal} in {@code FailingNavigationPointProvider}).
 */
public final class ThreadChunkCache {

  /** Number of slots; must be a power of two. 256 chunks cover a 256x256 block search area. */
  private static final int CAPACITY = 256;

  private static final int MASK = CAPACITY - 1;

  /** Resetting once half full keeps linear probe chains short. */
  private static final int MAX_ENTRIES = CAPACITY / 2;

  /** Decoded chunks are served at most this long before the shared cache is consulted again. */
  private static final long MAX_AGE_MS = 30_000;

  /** Lookups between age checks, so the hot path rarely reads the clock. */
  private static final int AGE_CHECK_INTERVAL = 1024;

  private final long[] keys = new long[CAPACITY];
  private final DecodedChunk[] snapshots = new DecodedChunk[CAPACITY];

  private UUID worldId;
  private long generation = Long.MIN_VALUE;
  private int size;
  private int lookupsSinceAgeCheck;
  private long lastResetTime;

  /**
   * Returns the cached snapshot for the given chunk key, or null if absent. This call also
   * (re)binds the cache to the given world and invalidation generation, so it must precede any
   * {@link #store(long, ChunkSnapshot)} call.
   */
  public DecodedChunk lookup(UUID world, long generation, long chunkKey) {
    if (generation != this.generation || !world.equals(this.worldId) || outlivedMaxAge()) {
      reset(world, generation);
      return null;
    }

    int index = indexFor(chunkKey);
    while (snapshots[index] != null) {
      if (keys[index] == chunkKey) return snapshots[index];
      index = (index + 1) & MASK;
    }
    return null;
  }

  /**
   * Caches a decoded chunk under the world and generation established by the preceding {@link
   * #lookup(UUID, long, long)}.
   */
  public void store(long chunkKey, DecodedChunk snapshot) {
    if (size >= MAX_ENTRIES) clearTable();

    int index = indexFor(chunkKey);
    while (snapshots[index] != null) {
      if (keys[index] == chunkKey) {
        snapshots[index] = snapshot;
        return;
      }
      index = (index + 1) & MASK;
    }
    keys[index] = chunkKey;
    snapshots[index] = snapshot;
    size++;
  }

  private boolean outlivedMaxAge() {
    if (++lookupsSinceAgeCheck < AGE_CHECK_INTERVAL) return false;
    lookupsSinceAgeCheck = 0;
    return System.currentTimeMillis() - lastResetTime > MAX_AGE_MS;
  }

  private void reset(UUID world, long generation) {
    clearTable();
    this.worldId = world;
    this.generation = generation;
    this.lastResetTime = System.currentTimeMillis();
    this.lookupsSinceAgeCheck = 0;
  }

  private void clearTable() {
    Arrays.fill(snapshots, null);
    size = 0;
  }

  private static int indexFor(long chunkKey) {
    long hash = chunkKey * 0x9E3779B97F4A7C15L;
    return (int) (hash >>> 32) & MASK;
  }
}
