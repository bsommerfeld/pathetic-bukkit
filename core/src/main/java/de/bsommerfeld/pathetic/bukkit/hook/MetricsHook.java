package de.bsommerfeld.pathetic.bukkit.hook;

import de.bsommerfeld.pathetic.api.pathing.hook.PathfinderHook;
import de.bsommerfeld.pathetic.api.pathing.hook.PathfindingContext;
import de.bsommerfeld.pathetic.bukkit.util.BStatsUtil;

public class MetricsHook implements PathfinderHook {
  @Override
  public void onPathfindingStep(PathfindingContext pathfindingContext) {
    BStatsUtil.incrementPathfindingSteps();
  }
}
