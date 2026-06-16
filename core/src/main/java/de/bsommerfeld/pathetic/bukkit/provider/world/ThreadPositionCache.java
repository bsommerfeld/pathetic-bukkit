package de.bsommerfeld.pathetic.bukkit.provider.world;

import de.bsommerfeld.pathetic.api.provider.NavigationPoint;
import java.util.Arrays;
import java.util.UUID;

/**
 * A {@link NavigationPoint} cache held once per pathfinding thread, keyed by a packed block
 * position. It is the sibling of {@link ThreadChunkCache} one level up the hot path: validation and
 * cost processors sample the same block repeatedly (a block is the clearance of one node, the ground
 * of the node above it, and a side of its horizontal neighbours), and those samples are clustered in
 * time because the search expands a frontier. Memoising the produced navigation point collapses that
 * redundancy into a single chunk lookup + material decode + allocation per distinct position.
 *
 * <p>The table grows on demand from a small initial size up to {@link #MAX_CAPACITY}. This keeps the
 * cost of a fresh thread cheap — a short path that touches a few hundred positions allocates a few
 * KB, not the worst-case table — which matters because the engine's async pool retires idle threads,
 * so a search after a pause lands on a new thread and re-pays this allocation. Capacity is monotonic
 * per thread: it never shrinks, so a thread that once ran a large search keeps its table.
 *
 * <p>Invalidation mirrors {@link ThreadChunkCache}: the cache is bound to a (world, invalidation
 * generation) pair and clears itself when either changes, with a {@link #MAX_AGE_MS} safety net.
 * Not thread-safe by design; instances must be thread-confined (see usage via {@code ThreadLocal} in
 * {@code FailingNavigationPointProvider}).
 */
public final class ThreadPositionCache {

  /** Initial slot count; a short path never grows past it. Must be a power of two. */
  private static final int INITIAL_CAPACITY = 512;

  /** Upper bound on slot count; a long search's frontier fits well within this. Power of two. */
  private static final int MAX_CAPACITY = 8192;

  /** Navigation points are served at most this long before the cache is dropped. */
  private static final long MAX_AGE_MS = 30_000;

  /** Lookups between age checks, so the hot path rarely reads the clock. */
  private static final int AGE_CHECK_INTERVAL = 1024;

  private long[] keys;
  private NavigationPoint[] points;
  private int mask;
  private int growThreshold;

  private UUID worldId;
  private long generation = Long.MIN_VALUE;
  private int size;
  private int lookupsSinceAgeCheck;
  private long lastResetTime;

  public ThreadPositionCache() {
    allocate(INITIAL_CAPACITY);
  }

  /**
   * Returns the cached navigation point for the given packed position, or null if absent. This call
   * also (re)binds the cache to the given world and invalidation generation, so it must precede any
   * {@link #store(long, NavigationPoint)} call.
   */
  public NavigationPoint lookup(UUID world, long generation, long positionKey) {
    if (generation != this.generation || !world.equals(this.worldId) || outlivedMaxAge()) {
      reset(world, generation);
      return null;
    }

    int index = indexFor(positionKey);
    while (points[index] != null) {
      if (keys[index] == positionKey) return points[index];
      index = (index + 1) & mask;
    }
    return null;
  }

  /**
   * Caches a navigation point under the world and generation established by the preceding {@link
   * #lookup(UUID, long, long)}.
   */
  public void store(long positionKey, NavigationPoint point) {
    if (size >= growThreshold) {
      if (keys.length < MAX_CAPACITY) {
        growAndRehash();
      } else {
        clearTable(); // at the cap: reset and repopulate, as a bounded cache
      }
    }
    insert(positionKey, point);
  }

  private void insert(long positionKey, NavigationPoint point) {
    int index = indexFor(positionKey);
    while (points[index] != null) {
      if (keys[index] == positionKey) {
        points[index] = point;
        return;
      }
      index = (index + 1) & mask;
    }
    keys[index] = positionKey;
    points[index] = point;
    size++;
  }

  private void growAndRehash() {
    long[] oldKeys = keys;
    NavigationPoint[] oldPoints = points;
    allocate(oldKeys.length * 2);
    for (int i = 0; i < oldPoints.length; i++) {
      if (oldPoints[i] != null) insert(oldKeys[i], oldPoints[i]);
    }
  }

  private void allocate(int capacity) {
    this.keys = new long[capacity];
    this.points = new NavigationPoint[capacity];
    this.mask = capacity - 1;
    this.growThreshold = capacity / 2; // keep linear-probe chains short
    this.size = 0;
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
    Arrays.fill(points, null);
    size = 0;
  }

  private int indexFor(long positionKey) {
    long hash = positionKey * 0x9E3779B97F4A7C15L;
    return (int) (hash >>> 32) & mask;
  }
}
