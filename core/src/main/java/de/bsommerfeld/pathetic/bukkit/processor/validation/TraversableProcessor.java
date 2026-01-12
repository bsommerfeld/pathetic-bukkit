package de.bsommerfeld.pathetic.bukkit.processor.validation;

import de.bsommerfeld.pathetic.api.pathing.processing.ValidationProcessor;
import de.bsommerfeld.pathetic.api.pathing.processing.context.EvaluationContext;

/**
 * The TraversableProcessor class implements the ValidationProcessor interface and is responsible
 * for determining whether a specific node in a path is traversable.
 *
 * <p>This processor validates nodes by utilizing the provided NodeEvaluationContext, which holds
 * information about the current path position, environment context, and the pathfinding
 * configuration. The validation is achieved by querying the node's traversability through the
 * navigation point provided by the context.
 */
public class TraversableProcessor implements ValidationProcessor {

  @Override
  public boolean isValid(EvaluationContext context) {
    return context
        .getPathfinderConfiguration()
        .getProvider()
        .getNavigationPoint(context.getCurrentPathPosition(), context.getEnvironmentContext())
        .isTraversable();
  }
}
