package de.bsommerfeld.pathetic.bukkit.provider.world;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs the periodic chunk-cache sweep that drops cooled-to-zero chunks, so the heat-based cache
 * self-sizes to its live working set instead of relying on the size ceiling to force pruning. A
 * single daemon thread; best-effort, so a failing sweep is swallowed and the next one still runs.
 */
public final class CacheSweeper {

  private static volatile ScheduledExecutorService executor;

  private CacheSweeper() {}

  /** Starts the sweep at a fixed interval; a no-op if already running. */
  public static synchronized void start(Runnable sweep, long intervalMs) {
    if (executor != null) return;
    ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "pathetic-cache-sweeper");
              thread.setDaemon(true);
              return thread;
            });
    scheduler.scheduleAtFixedRate(
        () -> {
          try {
            sweep.run();
          } catch (Throwable ignored) {
            // Best-effort maintenance; never let a sweep failure kill the schedule.
          }
        },
        intervalMs,
        intervalMs,
        TimeUnit.MILLISECONDS);
    executor = scheduler;
  }

  /** Stops the sweep. Subsequent {@link #start(Runnable, long)} calls start a fresh schedule. */
  public static synchronized void shutdown() {
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
  }
}
