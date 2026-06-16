package de.bsommerfeld.pathetic.example.config;

import de.bsommerfeld.pathetic.api.factory.PathfinderFactory;
import de.bsommerfeld.pathetic.api.pathing.Pathfinder;
import de.bsommerfeld.pathetic.engine.factory.AStarPathfinderFactory;

/**
 * Owns the live {@link Pathfinder} and the {@link PathfinderSettings} it was built from.
 *
 * <p>Holding the pathfinder behind {@link #pathfinder()} (rather than handing the instance out
 * directly) lets the example swap in a freshly configured pathfinder whenever the settings change,
 * so {@code /path config} edits take effect without a restart or rebuild.
 */
public final class PathfinderManager {

  private final PathfinderFactory factory = new AStarPathfinderFactory();
  private final PathfinderSettings settings;

  // Read on the command thread, replaced on the main thread after a config change.
  private volatile Pathfinder pathfinder;

  public PathfinderManager(PathfinderSettings settings) {
    this.settings = settings;
    rebuild();
  }

  /** The current pathfinder. Always call this instead of caching the instance. */
  public Pathfinder pathfinder() {
    return pathfinder;
  }

  public PathfinderSettings settings() {
    return settings;
  }

  /** Recreates the pathfinder from the current settings. */
  public void rebuild() {
    this.pathfinder = factory.createPathfinder(settings.toConfiguration());
  }
}
