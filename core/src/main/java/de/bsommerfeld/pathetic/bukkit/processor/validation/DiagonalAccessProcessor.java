package de.bsommerfeld.pathetic.bukkit.processor.validation;

import de.bsommerfeld.pathetic.api.pathing.context.EnvironmentContext;
import de.bsommerfeld.pathetic.api.pathing.processing.ValidationProcessor;
import de.bsommerfeld.pathetic.api.pathing.processing.context.EvaluationContext;
import de.bsommerfeld.pathetic.api.provider.NavigationPointProvider;
import de.bsommerfeld.pathetic.api.wrapper.PathPosition;

/**
 * Prevents an entity from cutting through the corner of a diagonal wall.
 *
 * <p>A diagonal step (such as those produced by {@code NeighbourStrategy.DIAGONAL_3D}) moves
 * between two cells that differ on more than one axis. On its own the engine only checks the source
 * and destination cells, so a move can pass diagonally between two solid blocks that touch at a
 * corner &mdash; squeezing through a "diagonal wall" that an entity could never actually traverse.
 * This happens on the horizontal plane just as much as it does for steps that also move up or down.
 *
 * <p>This processor validates the transition itself rather than the destination node. It looks at
 * the orthogonal neighbours of the source that lie in the direction of the step &mdash; the cells
 * sharing a face with the source, one per changed axis. The entity only needs <em>one</em> of those
 * to be open: that is a real way around the corner, so the move does not clip through anything. The
 * step is rejected only when <em>every</em> such neighbour is blocked &mdash; the source and
 * destination are then joined by nothing but a bare corner, the true "diagonal wall".
 *
 * <p>Purely orthogonal steps (and the initial start node, which has no predecessor) are always
 * allowed: there is no corner to cut.
 */
public class DiagonalAccessProcessor implements ValidationProcessor {

  private final double height;

  /**
   * @param height the vertical clearance, in blocks, an entity needs in order to pass through a
   *     cell; must not be negative. Should match the height used by other validators (e.g. {@link
   *     WalkableProcessor}) so a diagonal step is held to the same clearance as the cells it
   *     connects.
   */
  public DiagonalAccessProcessor(double height) {
    if (height < 0) {
      throw new IllegalArgumentException("height must not be negative: " + height);
    }
    this.height = height;
  }

  @Override
  public boolean isValid(EvaluationContext context) {
    PathPosition current = context.getCurrentPathPosition();
    PathPosition previous = context.getPreviousPathPosition();

    // The start node has no transition to validate.
    if (previous == null) {
      return true;
    }

    int dx = current.getFlooredX() - previous.getFlooredX();
    int dy = current.getFlooredY() - previous.getFlooredY();
    int dz = current.getFlooredZ() - previous.getFlooredZ();

    // An orthogonal step changes a single axis; there is no corner to cut.
    int changedAxes = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
    if (changedAxes < 2) {
      return true;
    }

    NavigationPointProvider provider = context.getNavigationPointProvider();
    EnvironmentContext environment = context.getEnvironmentContext();

    return hasOpenAccess(provider, environment, previous, dx, dy, dz);
  }

  /**
   * Returns whether at least one orthogonal neighbour of the source &mdash; one per changed axis,
   * the cells sharing a face with the source in the direction of the step &mdash; is traversable.
   * Such a cell is a genuine way around the corner, so the diagonal step does not clip a solid
   * block. Only when every one of them is blocked is the step a true diagonal wall and rejected.
   */
  private boolean hasOpenAccess(
      NavigationPointProvider provider,
      EnvironmentContext environment,
      PathPosition source,
      int dx,
      int dy,
      int dz) {
    return (dx != 0 && hasClearance(provider, source.add(dx, 0, 0), environment))
        || (dy != 0 && hasClearance(provider, source.add(0, dy, 0), environment))
        || (dz != 0 && hasClearance(provider, source.add(0, 0, dz), environment));
  }

  /**
   * Checks that every block from {@code base} up to the configured {@link #height} is traversable,
   * leaving room for the entity's body to sweep through the cell during the diagonal step.
   */
  private boolean hasClearance(
      NavigationPointProvider provider, PathPosition base, EnvironmentContext environment) {
    for (int offset = 0; offset <= height; offset++) {
      PathPosition layer = base.add(0, offset, 0);
      if (!provider.getNavigationPoint(layer, environment).isTraversable()) {
        return false;
      }
    }
    return true;
  }
}
