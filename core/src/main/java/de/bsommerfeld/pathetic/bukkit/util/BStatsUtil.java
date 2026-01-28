package de.bsommerfeld.pathetic.bukkit.util;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Utility class for tracking pathfinding steps in a thread-safe manner.
 * This class is designed to be used in a multi-threaded environment where
 * multiple threads may increment the pathfinding steps counter concurrently.
 */
public final class BStatsUtil {

  private static final AtomicInteger PATHFINDING_STEPS = new AtomicInteger(0);

  // Prevent instantiation
  private BStatsUtil() {
    throw new AssertionError("Utility class should not be instantiated");
  }

  /**
   * Increments the pathfinding steps counter atomically.
   * This method is thread-safe and can be called from multiple threads.
   */
  public static void incrementPathfindingSteps() {
    PATHFINDING_STEPS.incrementAndGet();
  }

  /**
   * Returns the current value of the pathfinding steps counter.
   * This method is thread-safe and returns the most recent value.
   *
   * @return the current number of pathfinding steps
   */
  public static int getPathfindingSteps() {
    return PATHFINDING_STEPS.get();
  }
}