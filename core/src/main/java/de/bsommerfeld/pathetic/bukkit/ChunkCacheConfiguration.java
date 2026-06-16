package de.bsommerfeld.pathetic.bukkit;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tunables for the shared chunk cache, passed to {@link PatheticBukkit#initialize(
 * org.bukkit.plugin.java.JavaPlugin, ChunkCacheConfiguration)}. All values have sensible defaults
 * ({@link #defaults()}); override only what you need via {@link #builder()}.
 *
 * <p>The cache retains chunks by <em>heat</em>: every access adds one heat (up to {@link #maxHeat()})
 * and idle time removes one heat per {@link #heatDecayInterval() decay interval}; a chunk that cools
 * to zero is dropped. So a chunk accessed once survives one decay interval idle, a chunk accessed
 * five times survives five — e.g. a 20-second decay interval means 20s, 40s, 60s … of idle life. A
 * background sweep every {@link #sweepInterval() sweep interval} prunes cooled chunks so the cache
 * self-sizes to its live working set, bounded above by {@link #maxCachedChunks()}.
 */
public final class ChunkCacheConfiguration {

  private static volatile ChunkCacheConfiguration active = builder().build();

  private final long heatDecayIntervalMs;
  private final int maxHeat;
  private final int maxCachedChunks;
  private final long sweepIntervalMs;
  private final boolean memoryPressureEviction;
  private final int minFreeHeapPercent;
  private final ExecutorService prefetchExecutor;

  private ChunkCacheConfiguration(Builder builder) {
    this.heatDecayIntervalMs = builder.heatDecayIntervalMs;
    this.maxHeat = builder.maxHeat;
    this.maxCachedChunks = builder.maxCachedChunks;
    this.sweepIntervalMs = builder.sweepIntervalMs;
    this.memoryPressureEviction = builder.memoryPressureEviction;
    this.minFreeHeapPercent = builder.minFreeHeapPercent;
    this.prefetchExecutor = builder.prefetchExecutor;
  }

  /** The default configuration (1-minute decay and sweep, 5 heat levels, heap-scaled size limit). */
  public static ChunkCacheConfiguration defaults() {
    return builder().build();
  }

  public static Builder builder() {
    return new Builder();
  }

  /** The currently applied configuration; read by the cache when it is created. */
  public static ChunkCacheConfiguration active() {
    return active;
  }

  static void apply(ChunkCacheConfiguration configuration) {
    active = configuration;
  }

  /** Milliseconds of idle time that removes one heat from a chunk. */
  public long heatDecayIntervalMs() {
    return heatDecayIntervalMs;
  }

  /** Maximum heat a chunk can accumulate; also the number of decay intervals it survives idle. */
  public int maxHeat() {
    return maxHeat;
  }

  /** Hard ceiling on cached chunks per world (an out-of-memory backstop, not the working size). */
  public int maxCachedChunks() {
    return maxCachedChunks;
  }

  /** Milliseconds between background sweeps that prune cooled-to-zero chunks. */
  public long sweepIntervalMs() {
    return sweepIntervalMs;
  }

  /**
   * Whether to evict cold chunks early when free heap runs low, on top of the size ceiling. Off by
   * default; turning it on makes the cache use only the memory that is actually free — "you get what
   * you use" — and pre-empts an out-of-memory failure under sustained heavy pathfinding.
   */
  public boolean memoryPressureEviction() {
    return memoryPressureEviction;
  }

  /** Below this percentage of free heap, {@link #memoryPressureEviction()} starts shedding chunks. */
  public int minFreeHeapPercent() {
    return minFreeHeapPercent;
  }

  /**
   * A developer-supplied executor for background chunk prefetch/preload, or {@code null} to use
   * Pathetic's own pool. Must be a <em>separate</em> pool from the one running the searches — the
   * prefetch tasks do blocking disk reads, so sharing the search executor would starve searches.
   * Its lifecycle is the caller's; {@link PatheticBukkit#shutdown()} never stops a supplied executor.
   */
  public ExecutorService prefetchExecutor() {
    return prefetchExecutor;
  }

  /**
   * Default size ceiling derived from the heap: up to ~10% of max memory at a conservative ~64 KiB
   * per pathing-touched chunk, clamped to a sane range, so it scales with the server instead of a
   * fixed number throttling big hosts or risking OOM on small ones.
   */
  public static int heapScaledMaxChunks() {
    long ceiling = (Runtime.getRuntime().maxMemory() / 10) / (64L * 1024);
    return (int) Math.max(8192, Math.min(ceiling, 262_144));
  }

  public static final class Builder {

    private long heatDecayIntervalMs = TimeUnit.MINUTES.toMillis(1);
    private int maxHeat = 5;
    private int maxCachedChunks = heapScaledMaxChunks();
    private long sweepIntervalMs = TimeUnit.MINUTES.toMillis(1);
    private boolean memoryPressureEviction = false;
    private int minFreeHeapPercent = 15;
    private ExecutorService prefetchExecutor = null;

    /** How long one heat level lasts; a dev wanting fast cooling can pass e.g. {@code (20, SECONDS)}. */
    public Builder heatDecayInterval(long duration, TimeUnit unit) {
      this.heatDecayIntervalMs = unit.toMillis(duration);
      return this;
    }

    public Builder maxHeat(int maxHeat) {
      this.maxHeat = maxHeat;
      return this;
    }

    public Builder maxCachedChunks(int maxCachedChunks) {
      this.maxCachedChunks = maxCachedChunks;
      return this;
    }

    /** How often the background sweep prunes cooled chunks. */
    public Builder sweepInterval(long duration, TimeUnit unit) {
      this.sweepIntervalMs = unit.toMillis(duration);
      return this;
    }

    /** Enables/disables evicting cold chunks early when free heap is low (off by default). */
    public Builder memoryPressureEviction(boolean enabled) {
      this.memoryPressureEviction = enabled;
      return this;
    }

    /** Free-heap percentage below which memory-pressure eviction kicks in (1..99). */
    public Builder minFreeHeapPercent(int percent) {
      this.minFreeHeapPercent = percent;
      return this;
    }

    /**
     * Routes background prefetch onto your own executor instead of Pathetic's pool. Use a pool
     * <em>separate</em> from your search executor (prefetch does blocking IO). Pathetic never shuts
     * a supplied executor down.
     */
    public Builder prefetchExecutor(ExecutorService prefetchExecutor) {
      this.prefetchExecutor = prefetchExecutor;
      return this;
    }

    public ChunkCacheConfiguration build() {
      if (heatDecayIntervalMs <= 0) {
        throw new IllegalArgumentException("heatDecayInterval must be positive");
      }
      if (maxHeat < 1) throw new IllegalArgumentException("maxHeat must be at least 1");
      if (maxCachedChunks < 1) throw new IllegalArgumentException("maxCachedChunks must be at least 1");
      if (sweepIntervalMs <= 0) throw new IllegalArgumentException("sweepInterval must be positive");
      if (minFreeHeapPercent < 1 || minFreeHeapPercent > 99) {
        throw new IllegalArgumentException("minFreeHeapPercent must be between 1 and 99");
      }
      return new ChunkCacheConfiguration(this);
    }
  }
}
