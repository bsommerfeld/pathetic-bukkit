package de.bsommerfeld.pathetic.bukkit.processor.validation;

import de.bsommerfeld.pathetic.api.pathing.context.EnvironmentContext;
import de.bsommerfeld.pathetic.api.pathing.processing.ValidationProcessor;
import de.bsommerfeld.pathetic.api.pathing.processing.context.EvaluationContext;
import de.bsommerfeld.pathetic.api.provider.NavigationPointProvider;
import de.bsommerfeld.pathetic.api.wrapper.PathPosition;
import de.bsommerfeld.pathetic.bukkit.provider.BukkitNavigationPoint;

public class WalkableProcessor implements ValidationProcessor {

  private final double height;

  public WalkableProcessor(double height) {
    this.height = height;
  }

  @Override
  public boolean isValid(EvaluationContext nodeEvaluationContext) {
    PathPosition pathPosition = nodeEvaluationContext.getCurrentPathPosition();
    PathPosition underPosition = pathPosition.subtract(0, 1, 0);

    NavigationPointProvider provider = nodeEvaluationContext.getNavigationPointProvider();
    EnvironmentContext environmentContext = nodeEvaluationContext.getEnvironmentContext();

    BukkitNavigationPoint bukkitNavigationPoint =
        (BukkitNavigationPoint) provider.getNavigationPoint(underPosition, environmentContext);

    return bukkitNavigationPoint.getMaterial().isSolid()
        && provider.getNavigationPoint(pathPosition, environmentContext).isTraversable()
        && areBlocksAbovePassable(provider, pathPosition, environmentContext);
  }

  private boolean areBlocksAbovePassable(
      NavigationPointProvider provider,
      PathPosition pathPosition,
      EnvironmentContext environmentContext) {
    for (double height = 0; height <= this.height; height++) {
      PathPosition position = pathPosition.add(0, height, 0);
      if (!provider.getNavigationPoint(position, environmentContext).isTraversable()) return false;
    }
    return true;
  }
}
