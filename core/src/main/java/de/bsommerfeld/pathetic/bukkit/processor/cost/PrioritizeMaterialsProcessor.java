package de.bsommerfeld.pathetic.bukkit.processor.cost;

import de.bsommerfeld.pathetic.api.pathing.processing.Cost;
import de.bsommerfeld.pathetic.api.pathing.processing.CostProcessor;
import de.bsommerfeld.pathetic.api.pathing.processing.context.EvaluationContext;
import de.bsommerfeld.pathetic.api.provider.NavigationPointProvider;
import de.bsommerfeld.pathetic.api.wrapper.PathPosition;
import de.bsommerfeld.pathetic.bukkit.provider.BukkitNavigationPoint;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.Material;

/**
 * A cost processor that calculates a cost contribution based on the presence of specific materials
 * at a navigation point. If the material at the current position matches any of the prioritized
 * materials, the specified cost is applied. Otherwise, no cost is contributed.
 */
public class PrioritizeMaterialsProcessor implements CostProcessor {

  private static final double DEFAULT_COST = 20.0;

  private final Set<Material> materials = new HashSet<>();
  private final double cost;

  /**
   * Constructs a new PrioritizeMaterialsProcessor with the specified cost and materials.
   *
   * @param cost the cost to be applied if the current material matches any of the prioritized
   *     materials
   * @param materials the materials to prioritize, which determine if the cost should be applied
   */
  public PrioritizeMaterialsProcessor(double cost, Material... materials) {
    this.cost = cost;
    this.materials.addAll(Arrays.asList(materials));
  }

  /**
   * Constructs a new PrioritizeMaterialsProcessor with the default cost (20.0) and the specified
   * materials.
   *
   * @param materials the materials to prioritize, which determine if the cost should be applied
   */
  public PrioritizeMaterialsProcessor(Material... materials) {
    this(DEFAULT_COST, materials);
  }

  @Override
  public Cost calculateCostContribution(EvaluationContext evaluationContext) {
    PathPosition current = evaluationContext.getCurrentPathPosition();
    NavigationPointProvider provider = evaluationContext.getNavigationPointProvider();
    BukkitNavigationPoint bnp = (BukkitNavigationPoint) provider.getNavigationPoint(current);

    if (materials.contains(bnp.getMaterial())) return Cost.of(cost);

    return Cost.ZERO;
  }
}
