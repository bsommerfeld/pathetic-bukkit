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

public class PrioritizeMaterialsProcessor implements CostProcessor {

  private static final double DEFAULT_COST = 20.0;

  private final Set<Material> materials = new HashSet<>();
  private final double cost;

  public PrioritizeMaterialsProcessor(double cost, Material... materials) {
    this.cost = cost;
    this.materials.addAll(Arrays.asList(materials));
  }

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
