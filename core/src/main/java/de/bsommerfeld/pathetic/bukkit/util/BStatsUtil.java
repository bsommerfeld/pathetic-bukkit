package de.bsommerfeld.pathetic.bukkit.util;

public class BStatsUtil {

  private static volatile int pathfinding_steps = 0;

  public static void incrementPathfindingSteps() {
    pathfinding_steps++;
  }

  public static int getPathfindingSteps() {
    return pathfinding_steps;
  }
}
