package de.bsommerfeld.pathetic.example.processor;

import de.bsommerfeld.pathetic.api.pathing.processing.Cost;
import de.bsommerfeld.pathetic.api.pathing.processing.NodeCostProcessor;
import de.bsommerfeld.pathetic.api.pathing.processing.context.NodeEvaluationContext;
import de.bsommerfeld.pathetic.api.provider.NavigationPointProvider;
import de.bsommerfeld.pathetic.bukkit.provider.BukkitNavigationPoint;
import org.bukkit.Material;

/**
 * The SimpleCostProcessor class implements the NodeCostProcessor interface and provides
 * a custom cost calculation mechanism for navigation nodes.
 *
 * The cost calculation is based on the material found directly beneath the current
 * node's position. Specifically, the cost increases by a predefined value if the
 * underlying block is made of stone; otherwise, no additional cost is applied.
 *
 * This processor contributes to the overall cost in the pathfinding process based on the
 * evaluation of terrains and their attributes.
 */
public class SimpleCostProcessor implements NodeCostProcessor {

  @Override
  public Cost calculateCostContribution(NodeEvaluationContext context) {
    NavigationPointProvider provider = context.getNavigationPointProvider();

    BukkitNavigationPoint beneath =
        (BukkitNavigationPoint)
            provider.getNavigationPoint(context.getCurrentPathPosition().subtract(0, 1, 0));

    if (beneath.getMaterial() == Material.STONE) {
      return Cost.of(20);
    }

    return Cost.ZERO;
  }
}
