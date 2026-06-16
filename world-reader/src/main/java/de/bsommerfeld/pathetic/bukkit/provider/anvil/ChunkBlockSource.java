package de.bsommerfeld.pathetic.bukkit.provider.anvil;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.BlockState;

/**
 * The block-data backend a decoded chunk reads from. Decouples the decode/caching pipeline from
 * <em>where</em> the blocks come from, so the same hot path serves chunks taken live from the server
 * (a snapshot-backed source in core) and chunks read off-thread from the world's region files on disk
 * (the Anvil reader), without the rest of the provider knowing the difference.
 */
public interface ChunkBlockSource {

  /**
   * The material at the given coordinates ({@code x}, {@code z} are in-chunk 0..15; {@code y} is a
   * world coordinate). Called at most once per distinct block, because the decoded chunk memoizes
   * the result.
   */
  Material getMaterial(int x, int y, int z);

  /**
   * The backing {@link ChunkSnapshot} if this source has one (live server chunks), or {@code null}
   * if it does not (disk-backed chunks). Live chunks resolve their {@code BlockState} from it; disk
   * chunks reconstruct one via {@link #getBlockState(int, int, int)} instead.
   */
  ChunkSnapshot snapshotOrNull();

  /**
   * Reconstructs the {@link BlockState} at the given coordinates for sources without a snapshot, so
   * a processor can still read block data for an off-disk chunk exactly as it would for a loaded
   * one. Returns {@code null} for snapshot-backed sources (which resolve block state from the
   * snapshot instead) and may return {@code null} if the block data cannot be reconstructed.
   */
  default BlockState getBlockState(int x, int y, int z) {
    return null;
  }
}
