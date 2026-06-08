package de.bsommerfeld.pathetic.bukkit.processor.validation;

import de.bsommerfeld.pathetic.api.pathing.context.EnvironmentContext;
import de.bsommerfeld.pathetic.api.pathing.processing.ValidationProcessor;
import de.bsommerfeld.pathetic.api.pathing.processing.context.EvaluationContext;
import de.bsommerfeld.pathetic.api.provider.NavigationPointProvider;
import de.bsommerfeld.pathetic.api.wrapper.PathPosition;

/**
 * Validates that a position is walkable: there must be solid ground directly beneath it and enough
 * vertical clearance for an entity of the configured height to stand.
 *
 * <p>With an axis-aligned neighbour strategy (e.g. {@code VERTICAL_AND_HORIZONTAL}) climbing a step
 * has to be expressed as an {@code up} move followed by a {@code horizontal} move. The intermediate
 * {@code up} node is a "jump apex" that has no solid ground directly beneath it and would otherwise
 * be rejected, preventing the search from ever forming a staircase. When {@link #allowStepUp} is
 * enabled such an apex is accepted, but only under tightly bounded conditions (see
 * {@link #isStepUp}) so that flat walking, descending and diagonal strategies are unaffected and no
 * floating "towers" can be built.
 */
public class WalkableProcessor implements ValidationProcessor {

  /** Cardinal horizontal offsets (±x, ±z) used to look for an adjacent step/riser. */
  private static final int[][] HORIZONTAL_OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

  private final double height;
  private final boolean allowStepUp;

  /**
   * Creates a processor with single-block step-up (jump) support enabled.
   *
   * @param height the vertical clearance, in blocks, an entity needs in order to stand at a
   *     position; must not be negative.
   */
  public WalkableProcessor(double height) {
    this(height, true);
  }

  /**
   * @param height the vertical clearance, in blocks, an entity needs in order to stand at a
   *     position; must not be negative.
   * @param allowStepUp whether an entity may climb a single block (needed for staircases with
   *     axis-aligned neighbour strategies). Disable for entities that cannot jump.
   */
  public WalkableProcessor(double height, boolean allowStepUp) {
    if (height < 0) {
      throw new IllegalArgumentException("height must not be negative: " + height);
    }
    this.height = height;
    this.allowStepUp = allowStepUp;
  }

  @Override
  public boolean isValid(EvaluationContext context) {
    PathPosition position = context.getCurrentPathPosition();
    NavigationPointProvider provider = context.getNavigationPointProvider();
    EnvironmentContext environment = context.getEnvironmentContext();

    if (!hasClearance(provider, position, environment)) {
      return false;
    }
    return standsOnSolidGround(provider, position, environment)
        || isStepUp(context, provider, position, environment);
  }

  /** Checks that the block directly below {@code position} is solid enough to stand on. */
  private boolean standsOnSolidGround(
      NavigationPointProvider provider, PathPosition position, EnvironmentContext environment) {
    PathPosition ground = position.subtract(0, 1, 0);
    return !provider.getNavigationPoint(ground, environment).isTraversable();
  }

  /**
   * Checks that every block from the feet ({@code position}) up to the configured {@link #height} is
   * traversable, leaving room for the entity to stand.
   */
  private boolean hasClearance(
      NavigationPointProvider provider, PathPosition position, EnvironmentContext environment) {
    for (int offset = 0; offset <= height; offset++) {
      PathPosition layer = position.add(0, offset, 0);
      if (!provider.getNavigationPoint(layer, environment).isTraversable()) {
        return false;
      }
    }
    return true;
  }

  /**
   * Determines whether {@code position} is a valid "jump apex" while climbing a single block. This
   * lets axis-aligned strategies form a staircase ({@code up} then {@code horizontal}) without the
   * groundless apex being rejected.
   *
   * <p>To stay bounded and non-disruptive, all of the following must hold:
   *
   * <ul>
   *   <li>step-up support is enabled and a previous position exists;
   *   <li>the move is exactly one block straight up (same column);
   *   <li>the previous position itself stands on solid ground — the jump originates from footing,
   *       which caps the climb at one block and prevents stacked, floating apex nodes;
   *   <li>there is a solid block horizontally adjacent to the feet — an actual step/riser to climb
   *       onto, so no spurious apex nodes are generated over open ground.
   * </ul>
   */
  private boolean isStepUp(
      EvaluationContext context,
      NavigationPointProvider provider,
      PathPosition position,
      EnvironmentContext environment) {
    if (!allowStepUp) {
      return false;
    }
    PathPosition previous = context.getPreviousPathPosition();
    if (previous == null) {
      return false;
    }

    boolean straightUp =
        position.getFlooredX() == previous.getFlooredX()
            && position.getFlooredZ() == previous.getFlooredZ()
            && position.getFlooredY() == previous.getFlooredY() + 1;
    if (!straightUp) {
      return false;
    }

    return standsOnSolidGround(provider, previous, environment)
        && hasAdjacentStep(provider, previous, environment);
  }

  /** Checks whether any horizontally adjacent block at the feet level is solid (a step to climb). */
  private boolean hasAdjacentStep(
      NavigationPointProvider provider, PathPosition feet, EnvironmentContext environment) {
    for (int[] offset : HORIZONTAL_OFFSETS) {
      PathPosition side = feet.add(offset[0], 0, offset[1]);
      if (!provider.getNavigationPoint(side, environment).isTraversable()) {
        return true;
      }
    }
    return false;
  }
}
