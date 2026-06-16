package de.bsommerfeld.pathetic.bukkit.hook;

import de.bsommerfeld.pathetic.api.pathing.context.EnvironmentContext;
import de.bsommerfeld.pathetic.api.pathing.hook.PathfinderHook;
import de.bsommerfeld.pathetic.api.pathing.hook.PathfindingContext;
import de.bsommerfeld.pathetic.bukkit.context.BukkitEnvironmentContext;
import de.bsommerfeld.pathetic.bukkit.provider.world.ChunkCorridorPreloader;
import org.bukkit.World;

/**
 * Preloads the chunk corridor between a search's start and target the moment the search begins, so
 * long-distance pathfinding over unloaded terrain does not stall at every chunk border loading
 * chunks one at a time. The loading happens off the search thread (see {@link
 * ChunkCorridorPreloader}); the demand path remains the correctness fallback.
 *
 * <p>This complements the per-block hot-path optimisations: those make the work cheap once terrain
 * is in the snapshot caches, while this hook gets the terrain there in parallel ahead of the search
 * front for the one case the caches cannot help with — chunks that are not loaded at all.
 *
 * <p>Register it like any other hook via {@code
 * PathfinderConfiguration.builder().pathfindingHooks(...)}. It only acts when the environment is a
 * {@link BukkitEnvironmentContext}, so it is inert (and harmless) under other environments.
 */
public class PreloadingHook implements PathfinderHook {

  @Override
  public void onPathfindingStep(PathfindingContext pathfindingContext) {
    // No per-step work: preloading is a one-shot action taken at the start of the search.
  }

  /**
   * Invoked once at the start of a search, with the start as {@link
   * PathfindingContext#currentPosition()} and the destination as {@link
   * PathfindingContext#target()}. Walks the start to target chunk corridor and warms it in the
   * background.
   */
  @Override
  public void onPathfindingStart(PathfindingContext context) {
    EnvironmentContext environmentContext = context.environmentContext();
    if (!(environmentContext instanceof BukkitEnvironmentContext)) return;

    World world = ((BukkitEnvironmentContext) environmentContext).getWorld();
    if (world == null) return;

    ChunkCorridorPreloader.preload(world, context.currentPosition(), context.target());
  }
}
