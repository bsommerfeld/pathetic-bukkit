package de.bsommerfeld.pathetic.bukkit.provider.world;

import de.bsommerfeld.pathetic.api.wrapper.PathPosition;
import de.bsommerfeld.pathetic.bukkit.provider.FailingNavigationPointProvider;
import de.bsommerfeld.pathetic.bukkit.util.ChunkUtil;
import java.util.LinkedHashSet;
import java.util.Set;
import org.bukkit.World;

/**
 * Preloads the chunks roughly between a search's start and target so that long-distance searches do
 * not stall at every chunk border waiting for terrain to load serially.
 *
 * <p>Where {@link ChunkPrefetcher}'s demand-driven neighbour prefetch deliberately refuses to
 * trigger chunk loading (it only snapshots already-loaded chunks), this preloader is meant to run
 * once at the <em>start</em> of a search, with knowledge of the destination, and may load unloaded
 * terrain. The chunks along the start to target line — plus a small margin for the A* search
 * wandering off the straight line — are warmed in the background while the search runs, so the
 * snapshot copies (and any worldgen) overlap with computation instead of blocking it.
 *
 * <p>Everything is best-effort and capped: the demand path remains the correctness fallback, and at
 * most {@link #MAX_CHUNKS} chunks are submitted so a pathologically long path cannot trigger
 * unbounded world generation.
 */
public final class ChunkCorridorPreloader {

  /** Chebyshev radius of chunks warmed around each point on the start to target line. */
  private static final int MARGIN = 1;

  /**
   * Upper bound on chunks submitted per search. Preloading no longer generates terrain (it only
   * warms loaded chunks and reads already-generated ones from disk), so a generous cap is cheap —
   * it lets a long corridor's disk reads run in parallel ahead of the search front instead of the
   * front stalling on them serially. Kept at/below the cache ceiling so a preloaded corridor cannot
   * evict itself before the search reaches it.
   */
  private static final int MAX_CHUNKS = 4096;

  /**
   * Minimum start-to-target chunk distance worth preloading. Below it the demand path loads the few
   * chunks a short search needs without ever stalling, so preloading would only copy a corridor's
   * worth of snapshots the search never visits — pure allocation (and GC pressure) for no benefit.
   * This is what keeps the frequent short paths of entity AI cheap.
   */
  private static final int MIN_PRELOAD_CHUNK_DISTANCE = 8;

  private ChunkCorridorPreloader() {}

  /** Submits the start to target chunk corridor for background preloading (overlaps the search). */
  public static void preload(World world, PathPosition start, PathPosition target) {
    int startChunkX = start.getFlooredX() >> 4;
    int startChunkZ = start.getFlooredZ() >> 4;
    int targetChunkX = target.getFlooredX() >> 4;
    int targetChunkZ = target.getFlooredZ() >> 4;

    int chunkDistance =
        Math.max(Math.abs(targetChunkX - startChunkX), Math.abs(targetChunkZ - startChunkZ));
    if (chunkDistance < MIN_PRELOAD_CHUNK_DISTANCE) return; // short path: demand path handles it

    Set<Long> corridor = collectCorridor(startChunkX, startChunkZ, targetChunkX, targetChunkZ);

    for (long chunkKey : corridor) {
      int chunkX = (int) chunkKey;
      int chunkZ = (int) (chunkKey >> 32);
      ChunkPrefetcher.submit(() -> FailingNavigationPointProvider.preloadChunk(world, chunkX, chunkZ));
    }
  }

  /**
   * Collects the chunks to preload, in submission order. The straight start-to-target line comes
   * first so that a long path's whole route is covered before any budget is spent on the {@link
   * #MARGIN} (the speculative band for the search wandering off the line). A {@link LinkedHashSet}
   * preserves order — chunks nearest the start are read first — while deduplicating overlap, and at
   * most {@link #MAX_CHUNKS} chunks are collected.
   */
  private static Set<Long> collectCorridor(int x0, int z0, int x1, int z1) {
    Set<Long> corridor = new LinkedHashSet<>();

    int deltaX = x1 - x0;
    int deltaZ = z1 - z0;
    int steps = Math.max(Math.abs(deltaX), Math.abs(deltaZ));

    // 1) The line itself.
    for (int step = 0; step <= steps; step++) {
      double t = steps == 0 ? 0.0 : (double) step / steps;
      corridor.add(
          ChunkUtil.getChunkKey(
              (int) Math.round(x0 + deltaX * t), (int) Math.round(z0 + deltaZ * t)));
      if (corridor.size() >= MAX_CHUNKS) return corridor;
    }

    // 2) The margin around it, only if the line did not already exhaust the budget.
    for (int step = 0; step <= steps; step++) {
      double t = steps == 0 ? 0.0 : (double) step / steps;
      int chunkX = (int) Math.round(x0 + deltaX * t);
      int chunkZ = (int) Math.round(z0 + deltaZ * t);
      for (int marginX = -MARGIN; marginX <= MARGIN; marginX++) {
        for (int marginZ = -MARGIN; marginZ <= MARGIN; marginZ++) {
          if (marginX == 0 && marginZ == 0) continue; // line already collected
          corridor.add(ChunkUtil.getChunkKey(chunkX + marginX, chunkZ + marginZ));
          if (corridor.size() >= MAX_CHUNKS) return corridor;
        }
      }
    }
    return corridor;
  }
}
