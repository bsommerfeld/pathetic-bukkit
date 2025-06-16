package de.bsommerfeld.pathetic.bukkit.provider;

import de.bsommerfeld.pathetic.api.pathing.context.EnvironmentContext;
import de.bsommerfeld.pathetic.api.provider.NavigationPoint;
import de.bsommerfeld.pathetic.api.wrapper.PathPosition;
import de.bsommerfeld.pathetic.bukkit.context.BukkitEnvironmentContext;
import de.bsommerfeld.pathetic.bukkit.util.ChunkUtil;
import de.bsommerfeld.pathetic.engine.util.ErrorLogger;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;

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
   * Retrieves a {@link ChunkSnapshot} for the specified chunk coordinates within the given {@link
   * BukkitEnvironmentContext}.
   *
   * @param environmentContext The context providing the {@link World} to retrieve the chunk from.
   * @param chunkX The x-coordinate of the chunk.
   * @param chunkZ The z-coordinate of the chunk.
   * @return The {@link ChunkSnapshot} representing the specified chunk.
   */
  private static ChunkSnapshot retrieveChunkSnapshot(
      BukkitEnvironmentContext environmentContext, int chunkX, int chunkZ) {
    World bukkitWorld = Bukkit.getWorld(environmentContext.getWorldUID());
    return CHUNK_DATA_PROVIDER_RESOLVER
        .getChunkDataProvider()
        .getSnapshot(bukkitWorld, chunkX, chunkZ);
  }

  /**
   * Retrieves a chunk snapshot for the specified {@link PathPosition}, loading the chunk if
   * necessary. This method first checks if a snapshot is already cached; if not, it retrieves and
   * caches a new snapshot.
   *
   * @param position The {@link PathPosition} used to determine the chunk coordinates.
   * @return The {@link ChunkSnapshot} for the specified position.
   * @throws IllegalStateException If the chunk snapshot cannot be retrieved.
   */
  private static ChunkSnapshot retrieveSnapshot(
      PathPosition position, BukkitEnvironmentContext environmentContext) {
    int chunkX = position.getFlooredX() >> 4;
    int chunkZ = position.getFlooredZ() >> 4;

    Optional<ChunkSnapshot> chunkSnapshotOptional = getChunkSnapshot(position, environmentContext);

    return chunkSnapshotOptional.orElseGet(
        () -> {
          ChunkSnapshot chunkSnapshot = retrieveChunkSnapshot(environmentContext, chunkX, chunkZ);

          if (chunkSnapshot == null) {
            throw ErrorLogger.logFatalError(
                "Could not retrieve chunk snapshot for chunk at ("
                    + chunkX
                    + ", "
                    + chunkZ
                    + ") in world: "
                    + environmentContext.getWorldUID());
          }

          processChunkSnapshot(environmentContext, chunkX, chunkZ, chunkSnapshot);
          return chunkSnapshot;
        });
  }

  /**
   * Ensures that a {@link NavigationPoint} is available for the given {@link PathPosition} by
   * retrieving the necessary chunk snapshot and extracting the relevant block data.
   *
   * @param pathPosition The {@link PathPosition} to get the {@link NavigationPoint} for.
   * @return The {@link NavigationPoint} at the specified position.
   */
  private static NavigationPoint ensureNavigationPoint(
      PathPosition pathPosition, BukkitEnvironmentContext environmentContext) {
    int chunkX = pathPosition.getFlooredX() >> 4;
    int chunkZ = pathPosition.getFlooredZ() >> 4;

    ChunkSnapshot chunkSnapshot = retrieveSnapshot(pathPosition, environmentContext);
    int x = pathPosition.getFlooredX() - chunkX * 16;
    int z = pathPosition.getFlooredZ() - chunkZ * 16;

    Material material = ChunkUtil.getMaterial(chunkSnapshot, x, pathPosition.getFlooredY(), z);
    BlockState blockState =
        CHUNK_DATA_PROVIDER_RESOLVER
            .getChunkDataProvider()
            .getBlockState(chunkSnapshot, x, pathPosition.getFlooredY(), z);
    return new BukkitNavigationPoint(chunkSnapshot, material, blockState);
  }

  /**
   * {@inheritDoc}
   *
   * <p>This implementation first checks if the superclass can provide the {@link NavigationPoint}.
   * If not, it ensures that the necessary chunk is loaded and retrieves the {@link NavigationPoint}
   * data.
   */
  @Override
  public NavigationPoint getNavigationPoint(
      PathPosition pathPosition, EnvironmentContext environmentContext) {
    BukkitEnvironmentContext bukkitEnvironmentContext =
        (BukkitEnvironmentContext) environmentContext;
    NavigationPoint navigationPoint = super.getNavigationPoint(pathPosition, environmentContext);
    return navigationPoint == null
        ? ensureNavigationPoint(pathPosition, bukkitEnvironmentContext)
        : navigationPoint;
  }
}
