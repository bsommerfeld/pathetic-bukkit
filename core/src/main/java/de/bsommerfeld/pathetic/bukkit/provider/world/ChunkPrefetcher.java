package de.bsommerfeld.pathetic.bukkit.provider.world;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Daemon executor used to warm chunk snapshots in the background while a search is still working
 * through the current chunk. Prefetching is strictly best-effort: tasks may be dropped on shutdown
 * and failures are irrelevant, since every consumer falls back to fetching on demand.
 *
 * <p>By default this lazily creates its own pool. A developer may instead supply their own executor
 * via {@link #useExecutor(ExecutorService)} (configured through {@code ChunkCacheConfiguration}); its
 * lifecycle stays the caller's and {@link #shutdown()} never stops it.
 */
public final class ChunkPrefetcher {

  /** Disk reads (the bulk of prefetch/preload work) are IO-bound, so several threads overlap well. */
  private static final int THREADS = 6;

  private static volatile ExecutorService ownExecutor;
  private static volatile ExecutorService providedExecutor;

  private ChunkPrefetcher() {}

  /**
   * Routes prefetch work onto a developer-supplied executor instead of the internal pool; pass
   * {@code null} to revert. Use a pool separate from the search executor — prefetch does blocking
   * IO. The supplied executor is never shut down by {@link #shutdown()}.
   */
  public static void useExecutor(ExecutorService executor) {
    providedExecutor = executor;
  }

  /** Submits a best-effort prefetch task, starting the internal executor on first use. */
  public static void submit(Runnable task) {
    try {
      executor().execute(task);
    } catch (RejectedExecutionException ignored) {
      // Shut down concurrently; prefetching is best-effort.
    }
  }

  /** Eagerly starts the internal pool so the first search doesn't pay thread creation. */
  public static void ensureStarted() {
    if (providedExecutor == null) executor();
  }

  private static ExecutorService executor() {
    ExecutorService provided = providedExecutor;
    if (provided != null) return provided;

    ExecutorService current = ownExecutor;
    if (current == null) {
      synchronized (ChunkPrefetcher.class) {
        current = ownExecutor;
        if (current == null) {
          current = Executors.newFixedThreadPool(THREADS, threadFactory());
          ownExecutor = current;
        }
      }
    }
    return current;
  }

  /**
   * Stops Pathetic's own prefetch threads (a developer-supplied executor is left untouched) and
   * forgets any supplied executor. Subsequent {@link #submit(Runnable)} calls transparently start a
   * fresh internal pool.
   */
  public static synchronized void shutdown() {
    if (ownExecutor != null) {
      ownExecutor.shutdownNow();
      ownExecutor = null;
    }
    providedExecutor = null;
  }

  private static ThreadFactory threadFactory() {
    final AtomicInteger counter = new AtomicInteger();
    return runnable -> {
      Thread thread =
          new Thread(runnable, "pathetic-chunk-prefetcher-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }
}
