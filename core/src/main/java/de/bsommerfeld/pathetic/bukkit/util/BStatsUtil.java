package de.bsommerfeld.pathetic.bukkit.util;

import java.util.concurrent.atomic.LongAdder;

/**
 * Utility class for tracking pathfinding steps in a thread-safe manner.
 * This class is designed to be used in a multi-threaded environment where
 * multiple threads may increment the pathfinding steps counter concurrently.
 *
 * <p>Increments are recorded with a {@link LongAdder}, which spreads contention
 * across multiple internal cells instead of forcing every thread through a single
 * CAS loop. This keeps the increment path fast even when many pathfinding threads
 * report steps at once.
 *
 * <p>The counter is read as a delta: {@link #getPathfindingSteps()} returns the
 * number of steps accumulated since the previous read and resets the counter. This
 * matches the semantics of a bStats line chart (steps per submission interval) and
 * means the value can never grow without bound, so it never overflows.
 */
public final class BStatsUtil {

  private static final LongAdder PATHFINDING_STEPS = new LongAdder();

  // Prevent instantiation
  private BStatsUtil() {
    throw new AssertionError("Utility class should not be instantiated");
  }

  /**
   * Increments the pathfinding steps counter.
   * This method is thread-safe and can be called from multiple threads.
   */
  public static void incrementPathfindingSteps() {
    PATHFINDING_STEPS.increment();
  }

  /**
   * Returns the number of pathfinding steps recorded since the last call and resets
   * the counter to zero. Because the value is consumed on read, the counter never
   * accumulates without bound and therefore never reaches a numeric limit.
   *
   * <p>This method is thread-safe. If the number of steps in a single interval ever
   * exceeds {@link Integer#MAX_VALUE}, it is clamped to that value to satisfy the
   * {@code int} contract expected by the bStats chart.
   *
   * @return the number of pathfinding steps since the previous read
   */
  public static int getPathfindingSteps() {
    long steps = PATHFINDING_STEPS.sumThenReset();
    return steps > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) steps;
  }
}
