package de.bsommerfeld.pathetic.example.processor;

import de.bsommerfeld.pathetic.api.pathing.processing.NodeValidationProcessor;
import de.bsommerfeld.pathetic.api.pathing.processing.context.NodeEvaluationContext;

/**
 * The SimpleValidationProcessor class implements the NodeValidationProcessor interface and provides
 * a basic validation mechanism for pathfinding nodes.
 *
 * <p>The validation checks whether a navigation point at the current path position is traversable
 * based on its defined characteristics. Traversability is determined by the navigation point's
 * specific implementation, such as whether the material at that location is considered solid.
 *
 * <p>This processor acts as part of the node validation pipeline in the pathfinding configuration,
 * ensuring that nodes not meeting the traversability criteria are excluded from the pathfinding
 * process.
 */
public class SimpleValidationProcessor implements NodeValidationProcessor {

  @Override
  public boolean isValid(NodeEvaluationContext context) {
    return context
        .getPathfinderConfiguration()
        .getProvider()
        .getNavigationPoint(context.getCurrentPathPosition())
        .isTraversable();
  }
}
