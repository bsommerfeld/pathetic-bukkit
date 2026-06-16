package de.bsommerfeld.pathetic.bukkit.provider.world;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bounded chunk cache that retains chunks by <em>heat</em>: every access warms a chunk (+1, up to
 * {@code maxHeat}) and idle time cools it (−1 per decay interval). A chunk that cools to zero is
 * dropped, and on overflow the least-hot chunk is evicted — so the chunks that are pathed through
 * most (spawn, farms, common routes) stay resident while one-off terrain falls away.
 *
 * <p>Because heat resets on access, a continuously-used chunk never expires (unlike a fixed TTL,
 * which would drop even a hot chunk and force a re-fetch). Decay is computed lazily from the last
 * access time, so there is no background sweeper. An absolute {@link #HARD_MAX_AGE_MS} cap still
 * refreshes even a perpetually-hot chunk eventually, bounding staleness from changes that fire no
 * Bukkit event.
 *
 * <p>Heat and timestamps are plain volatile fields updated without locking; races only perturb a
 * heuristic, never correctness. Eviction samples a handful of entries rather than maintaining a
 * global order, keeping the put path cheap.
 */
final class BoundedChunkCache<V> {

  /** Entries examined per eviction. */
  private static final int SAMPLE_SIZE = 8;

  /** Even a perpetually-hot chunk is dropped after this long, so no-event edits can't pin a stale copy forever. */
  private static final long HARD_MAX_AGE_MS = TimeUnit.MINUTES.toMillis(30);

  /** Cold chunks shed per insert while under memory pressure; bounds the work and shrinks gradually. */
  private static final int PRESSURE_EVICTION_BATCH = 16;

  private final int maxSize;
  private final long decayIntervalMs;
  private final int maxHeat;
  private final boolean memoryPressureEviction;
  private final int minFreeHeapPercent;
  private final ConcurrentHashMap<Long, Node<V>> map = new ConcurrentHashMap<>();
  private final AtomicLong sequence = new AtomicLong();

  BoundedChunkCache(
      int maxSize,
      long decayIntervalMs,
      int maxHeat,
      boolean memoryPressureEviction,
      int minFreeHeapPercent) {
    this.maxSize = maxSize;
    this.decayIntervalMs = decayIntervalMs;
    this.maxHeat = maxHeat;
    this.memoryPressureEviction = memoryPressureEviction;
    this.minFreeHeapPercent = minFreeHeapPercent;
  }

  V get(long key) {
    Node<V> node = map.get(key);
    if (node == null) return null;
    long now = System.currentTimeMillis();
    int heat = effectiveHeat(node, now);
    if (heat == 0) {
      map.remove(key, node); // cooled to zero -> dropped
      return null;
    }
    node.heat = Math.min(maxHeat, heat + 1); // traffic warms it
    node.lastAccess = now;
    return node.value;
  }

  void put(long key, V value) {
    long now = System.currentTimeMillis();
    map.put(key, new Node<>(value, now, sequence.incrementAndGet()));
    if (map.size() > maxSize) evict(now);
    if (memoryPressureEviction && underMemoryPressure()) {
      // Shed a bounded batch of the coldest chunks so the next GC can reclaim them; sustained
      // pressure keeps shrinking the cache toward what the heap can actually spare.
      for (int i = 0; i < PRESSURE_EVICTION_BATCH && !map.isEmpty(); i++) {
        evict(now);
      }
    }
  }

  private boolean underMemoryPressure() {
    Runtime runtime = Runtime.getRuntime();
    long max = runtime.maxMemory();
    long used = runtime.totalMemory() - runtime.freeMemory();
    long freePercent = (max - used) * 100 / max;
    return freePercent < minFreeHeapPercent;
  }

  void remove(long key) {
    map.remove(key);
  }

  /** Membership test that does not warm the chunk (so prefetch checks don't skew heat). */
  boolean containsKey(long key) {
    Node<V> node = map.get(key);
    if (node == null) return false;
    if (effectiveHeat(node, System.currentTimeMillis()) == 0) {
      map.remove(key, node);
      return false;
    }
    return true;
  }

  int size() {
    return map.size();
  }

  /**
   * Actively removes every chunk that has cooled to zero heat. Lazy decay only prunes chunks that
   * happen to be accessed or sampled, so without this an idle chunk never touched again would linger
   * forever; running this periodically lets the cache self-size down to its live working set, which
   * is what keeps the bound a function of current load rather than the {@link #maxSize} ceiling.
   */
  void sweepCooled() {
    long now = System.currentTimeMillis();
    for (Map.Entry<Long, Node<V>> entry : map.entrySet()) {
      if (effectiveHeat(entry.getValue(), now) == 0) {
        map.remove(entry.getKey(), entry.getValue());
      }
    }
  }

  private void evict(long now) {
    long victimKey = 0;
    Node<V> victim = null;
    int victimHeat = Integer.MAX_VALUE;
    int seen = 0;
    for (Map.Entry<Long, Node<V>> entry : map.entrySet()) {
      Node<V> node = entry.getValue();
      int heat = effectiveHeat(node, now);
      if (heat == 0) {
        map.remove(entry.getKey(), node); // a fully-cooled chunk is the cheapest possible victim
        return;
      }
      if (heat < victimHeat || (heat == victimHeat && node.sequence < victim.sequence)) {
        victim = node;
        victimKey = entry.getKey();
        victimHeat = heat;
      }
      if (++seen >= SAMPLE_SIZE) break;
    }
    if (victim != null) map.remove(victimKey, victim);
  }

  private int effectiveHeat(Node<V> node, long now) {
    if (now - node.insertTime >= HARD_MAX_AGE_MS) return 0;
    long decayed = node.heat - (now - node.lastAccess) / decayIntervalMs;
    return decayed <= 0 ? 0 : (int) decayed;
  }

  private static final class Node<V> {
    private final V value;
    private final long insertTime;
    private final long sequence;
    private volatile int heat = 1;
    private volatile long lastAccess;

    Node(V value, long now, long sequence) {
      this.value = value;
      this.insertTime = now;
      this.lastAccess = now;
      this.sequence = sequence;
    }
  }
}
