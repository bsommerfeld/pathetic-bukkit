package de.bsommerfeld.pathetic.bukkit.processor.validation;

import de.bsommerfeld.pathetic.api.pathing.processing.NodeValidationProcessor;
import de.bsommerfeld.pathetic.api.pathing.processing.context.NodeEvaluationContext;

/**
 * The TraversableProcessor class implements the NodeValidationProcessor interface and is
 * responsible for determining whether a specific node in a path is traversable.
 *
 * <p>This processor validates nodes by utilizing the provided NodeEvaluationContext, which holds
 * information about the current path position, environment context, and the pathfinding
 * configuration. The validation is achieved by querying the node's traversability through the
 * navigation point provided by the context.
 */
public class TraversableProcessor implements NodeValidationProcessor {

  @Override
  public boolean isValid(NodeEvaluationContext context) {
    return context
        .getPathfinderConfiguration()
        .getProvider()
        .getNavigationPoint(context.getCurrentPathPosition(), context.getEnvironmentContext())
        .isTraversable();
  }
}
