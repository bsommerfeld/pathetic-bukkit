package de.bsommerfeld.pathetic.bukkit.provider;

import de.bsommerfeld.pathetic.api.provider.NavigationPoint;
import de.bsommerfeld.pathetic.bukkit.context.BukkitEnvironmentContext;
import de.bsommerfeld.pathetic.bukkit.provider.anvil.AnvilChunkLoader;
import de.bsommerfeld.pathetic.bukkit.provider.world.DecodedChunk;
import de.bsommerfeld.pathetic.bukkit.provider.world.WorldDomain;
import de.bsommerfeld.pathetic.bukkit.util.ChunkUtil;
import java.util.UUID;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;

/**
 * The {@code LoadingNavigationPointProvider} extends the {@link FailingNavigationPointProvider} and
 * provides an implementation for retrieving navigation point data from a Minecraft world, ensuring
 * that the required chunks are loaded if necessary.
 *
 * <p>This provider builds upon the functionality of {@link FailingNavigationPointProvider} by
 * actively loading chunks when they are not readily available, guaranteeing the retrieval of
 * navigation point data even in situations where chunks might not be loaded initially.
 */
public class LoadingNavigationPointProvider extends FailingNavigationPointProvider {

  /**
   * {@inheritDoc}
   *
   * <p>This implementation first lets the superclass serve the point from cached/loaded chunks. If
   * that misses, it ensures the necessary chunk is loaded and retrieves the {@link NavigationPoint}
   * from it.
   */
  @Override
  protected NavigationPoint computeNavigationPoint(
      BukkitEnvironmentContext environmentContext, int blockX, int blockY, int blockZ) {
    NavigationPoint navigationPoint =
        super.computeNavigationPoint(environmentContext, blockX, blockY, blockZ);
    return navigationPoint != null
        ? navigationPoint
        : ensureNavigationPoint(environmentContext, blockX, blockY, blockZ);
  }

  /**
   * Ensures that a {@link NavigationPoint} is available for the given block position by loading the
   * containing chunk if necessary and extracting the relevant block data.
   */
  private static NavigationPoint ensureNavigationPoint(
      BukkitEnvironmentContext environmentContext, int blockX, int blockY, int blockZ) {
    DecodedChunk decodedChunk =
        retrieveDecodedChunk(environmentContext, blockX >> 4, blockZ >> 4);
    return createNavigationPoint(decodedChunk, blockX & 0xF, blockY, blockZ & 0xF);
  }

  /**
   * Returns the decoded chunk for the given chunk coordinates, loading and caching it if it is not
   * already cached.
   *
   * @throws IllegalStateException If the chunk snapshot cannot be retrieved.
   */
  private static DecodedChunk retrieveDecodedChunk(
      BukkitEnvironmentContext environmentContext, int chunkX, int chunkZ) {
    World world = environmentContext.getWorld();
    UUID uuid = world.getUID();
    WorldDomain worldDomain = SNAPSHOTS_MAP.computeIfAbsent(uuid, unused -> new WorldDomain());
    long chunkKey = ChunkUtil.getChunkKey(chunkX, chunkZ);

    DecodedChunk cached = worldDomain.getDecodedOrNull(chunkKey);
    if (cached != null) return cached;

    // In-between step: for chunks that aren't loaded, read them straight from the world's region
    // files on disk — fast, parallel, no worldgen, no main-thread round trip. Only chunks that
    // aren't on disk at all fall through to the server, which may have to generate them. Loaded
    // chunks never reach here: the superclass already served them from a live snapshot.
    if (!world.isChunkLoaded(chunkX, chunkZ)) {
      DecodedChunk fromDisk =
          createDeduplicated(
              worldDomain, chunkKey, () -> AnvilChunkLoader.load(world, chunkX, chunkZ));
      if (fromDisk != null) return fromDisk;
    }

    ChunkSnapshot chunkSnapshot = retrieveChunkSnapshot(environmentContext, chunkX, chunkZ);
    if (chunkSnapshot == null) {
      throw new IllegalStateException(
          "Could not retrieve chunk snapshot for chunk at ("
              + chunkX
              + ", "
              + chunkZ
              + ") in world: "
              + world.getName());
    }

    return processChunkSnapshot(environmentContext, chunkX, chunkZ, chunkSnapshot);
  }

  /** Loads a {@link ChunkSnapshot} for the specified chunk coordinates, loading the chunk. */
  private static ChunkSnapshot retrieveChunkSnapshot(
      BukkitEnvironmentContext environmentContext, int chunkX, int chunkZ) {
    World bukkitWorld = environmentContext.getWorld();
    return CHUNK_DATA_PROVIDER_RESOLVER
        .getChunkDataProvider()
        .getSnapshot(bukkitWorld, chunkX, chunkZ);
  }
}
