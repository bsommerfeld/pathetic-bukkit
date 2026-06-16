package de.bsommerfeld.pathetic.bukkit.provider;

import de.bsommerfeld.pathetic.api.provider.NavigationPoint;
import de.bsommerfeld.pathetic.bukkit.provider.world.DecodedChunk;
import de.bsommerfeld.pathetic.provider.ChunkDataProvider;
import lombok.Getter;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.BlockState;

public class BukkitNavigationPoint implements NavigationPoint {

  private final DecodedChunk decodedChunk;
  @Getter private final Material material;

  private final int x;
  private final int y;
  private final int z;
  private final ChunkDataProvider chunkDataProvider;

  /**
   * The chunk's modification count this point was last validated against; see {@link #isStale()}.
   * Mutable, but the point lives in a thread-local position cache, so it is only ever touched by its
   * owning pathfinding thread.
   */
  private int chunkModCount;

  private BlockState blockState;

  public BukkitNavigationPoint(
      DecodedChunk decodedChunk,
      Material material,
      int x,
      int y,
      int z,
      ChunkDataProvider chunkDataProvider) {
    this.decodedChunk = decodedChunk;
    this.material = material;
    this.x = x;
    this.y = y;
    this.z = z;
    this.chunkDataProvider = chunkDataProvider;
    this.chunkModCount = decodedChunk.getModCount();
  }

  /**
   * Whether <em>this point's own block</em> changed since the point was built, so a cached point can
   * be revalidated cheaply. If the chunk's modCount is unchanged nothing in the chunk moved (the
   * common case, a single compare). If it moved but a different block changed, this point is still
   * valid and advances its stamp so subsequent checks are cheap again — so a block edit invalidates
   * only the points at the edited coordinates, not the whole chunk.
   */
  public boolean isStale() {
    int current = decodedChunk.getModCount();
    if (current == chunkModCount) return false;
    if (decodedChunk.changedSince(x, y, z, chunkModCount)) return true;
    chunkModCount = current;
    return false;
  }

  /** The backing chunk snapshot, or {@code null} for points read from disk. */
  public ChunkSnapshot getChunk() {
    return decodedChunk.getSnapshot();
  }

  /**
   * Returns the {@link BlockState} at this point, resolving it on first access. Creating a block
   * state is far more expensive than the palette lookups needed for pathfinding itself, so it is
   * deferred until actually requested.
   *
   * <p>Loaded chunks resolve it from their snapshot (unchanged behaviour); chunks read from disk
   * reconstruct it from the stored block data, so a processor sees the same block data either way.
   */
  public BlockState getBlockState() {
    if (blockState == null) {
      // An override (a block changed since caching) or a disk reconstruction takes precedence;
      // otherwise resolve from the live snapshot.
      BlockState resolved = decodedChunk.getBlockState(x, y, z);
      if (resolved == null) {
        ChunkSnapshot snapshot = decodedChunk.getSnapshot();
        if (snapshot != null) resolved = chunkDataProvider.getBlockState(snapshot, x, y, z);
      }
      blockState = resolved;
    }
    return blockState;
  }

  @Override
  public boolean isTraversable() {
    return !material.isSolid();
  }
}
