package de.bsommerfeld.pathetic.bukkit.util;

public class BStatsUtil {
  private static int paths_searched = 0;
  private static int path_distance = 0;

  public static void incrementPathsSearched() {
    paths_searched++;
  }

  public static void incrementPathDistance() {
    path_distance++;
  }

  public static int getPathsSearched() {
    return paths_searched;
  }

  public static int getPathDistance() {
    return path_distance;
  }
}
