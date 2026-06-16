package de.bsommerfeld.pathetic.bukkit.provider.world;

import de.bsommerfeld.pathetic.bukkit.provider.anvil.ChunkBlockSource;
import de.bsommerfeld.pathetic.bukkit.util.ChunkUtil;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;

/**
 * {@link ChunkBlockSource} backed by a live {@link ChunkSnapshot} taken from the server. This is the
 * default source for loaded chunks and preserves the exact pre-existing behaviour: materials come
 * from {@link ChunkUtil#getMaterial(ChunkSnapshot, int, int, int)} and the snapshot remains
 * available for lazy {@code BlockState} resolution.
 */
public final class SnapshotBlockSource implements ChunkBlockSource {

  private final ChunkSnapshot snapshot;

  public SnapshotBlockSource(ChunkSnapshot snapshot) {
    this.snapshot = snapshot;
  }

  @Override
  public Material getMaterial(int x, int y, int z) {
    return ChunkUtil.getMaterial(snapshot, x, y, z);
  }

  @Override
  public ChunkSnapshot snapshotOrNull() {
    return snapshot;
  }
}
