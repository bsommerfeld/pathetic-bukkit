package de.bsommerfeld.pathetic.bukkit.provider.world;

import de.bsommerfeld.pathetic.bukkit.provider.anvil.ChunkBlockSource;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;

/**
 * A {@link ChunkBlockSource} paired with a lazily populated material cache. It is the cached unit
 * everywhere {@code FailingNavigationPointProvider} stores chunk data, regardless of whether the
 * blocks come from a live server snapshot or from the world's region files on disk.
 *
 * <p>Resolving a block from the source can be relatively expensive — for {@link SnapshotBlockSource}
 * it is a registry lookup (Minecraft block to Bukkit {@link Material}) on top of the palette read,
 * and on the pathfinding hot path that would be paid hundreds of thousands of times, often for
 * blocks already resolved. This class resolves each distinct block at most once and serves every
 * subsequent read as a flat-array index, amortising the cost across the whole search and, because
 * the instance is shared through the snapshot caches, across every pathfinding thread that touches
 * the same chunk.
 *
 * <p>Resolution is done per 16x16x16 section and only for sections actually accessed, so a search
 * that stays within a vertical band never resolves the full column. Population is lock-free: section
 * arrays are published through a {@link ConcurrentHashMap} and individual slots are filled with a
 * benign race (concurrent resolvers compute the same value and the reference write is atomic).
 */
public final class DecodedChunk {

  /** Blocks in one 16x16x16 section: a flat index packed as {@code (x<<8)|((y&15)<<4)|z}. */
  private static final int SECTION_VOLUME = 16 * 16 * 16;

  private final ChunkBlockSource source;
  private final ConcurrentHashMap<Integer, Material[]> sections = new ConcurrentHashMap<>();

  /**
   * Block-data overrides applied since this chunk was cached, keyed by a packed in-chunk position.
   * Lets a block change patch a single block in place (see {@link #applyBlockChange}) instead of
   * forcing the whole chunk to be dropped and re-fetched. Stays empty for the overwhelmingly common
   * case of an unmodified cached chunk, so the {@link #getBlockState} fast path skips it.
   */
  private final ConcurrentHashMap<Integer, BlockData> overrides = new ConcurrentHashMap<>();

  /**
   * Bumped on every {@link #applyBlockChange}. A navigation point records the value it was built
   * under; if it differs, the point consults {@link #changedSince} to see whether <em>its own</em>
   * block changed, so a block edit invalidates only the points at the edited coordinates rather than
   * every point in the chunk (let alone every cache). Volatile: written on the (single-threaded)
   * event path, read on pathfinding threads, and its write publishes the section, override and
   * change-stamp updates made just before it.
   */
  private volatile int modCount;

  /**
   * For each block changed since this chunk was cached, the {@link #modCount} value at which it
   * changed. Lets a point at coordinates that did <em>not</em> change be reused even after a
   * different block in the same chunk was edited. Empty for the common unmodified chunk.
   */
  private final ConcurrentHashMap<Integer, Integer> changeStamps = new ConcurrentHashMap<>();

  public DecodedChunk(ChunkBlockSource source) {
    this.source = source;
  }

  /** The current modification count; navigation points compare against the value they captured. */
  public int getModCount() {
    return modCount;
  }

  /** Convenience factory for the common case of wrapping a live server snapshot. */
  public static DecodedChunk ofSnapshot(ChunkSnapshot snapshot) {
    return new DecodedChunk(new SnapshotBlockSource(snapshot));
  }

  /** The backing snapshot if there is one (live chunks), or {@code null} for disk-backed chunks. */
  public ChunkSnapshot getSnapshot() {
    return source.snapshotOrNull();
  }

  /**
   * Returns the {@link BlockState} at the given in-chunk coordinates: the overridden block data if
   * this block was changed since caching, otherwise the disk-backed reconstruction (or {@code null}
   * for snapshot-backed chunks, which resolve block state from the snapshot directly).
   */
  public BlockState getBlockState(int x, int y, int z) {
    if (!overrides.isEmpty()) {
      BlockData override = overrides.get(positionKey(x, y, z));
      if (override != null) return override.createBlockState();
    }
    return source.getBlockState(x, y, z);
  }

  /**
   * Returns the material at the given in-chunk coordinates ({@code x}, {@code z} in 0..15;
   * {@code y} in world coordinates), resolving each distinct block from the source at most once.
   */
  public Material getMaterial(int x, int y, int z) {
    Material[] section = sectionFor(y);
    int index = (x << 8) | ((y & 15) << 4) | z;
    Material material = section[index];
    if (material == null) {
      material = source.getMaterial(x, y, z);
      section[index] = material; // benign race: concurrent resolvers store the same value
    }
    return material;
  }

  /**
   * Patches a single block in place after a world modification, so a block change costs an array
   * write instead of dropping and re-fetching the whole chunk. The new material is written to the
   * section's fast-path array and the block data is recorded for {@link #getBlockState}. Threads see
   * the update via the invalidation-generation bump the caller performs immediately after, which
   * also resets the per-thread position caches holding now-stale navigation points.
   */
  public void applyBlockChange(int x, int y, int z, BlockData blockData) {
    int key = positionKey(x, y, z);
    int next = modCount + 1;
    sectionFor(y)[(x << 8) | ((y & 15) << 4) | z] = blockData.getMaterial();
    overrides.put(key, blockData);
    changeStamps.put(key, next);
    modCount = next; // volatile write last; publishes the section, override and stamp writes above
  }

  /**
   * Whether the block at the given in-chunk coordinates changed after {@code sinceModCount} (i.e. a
   * navigation point built at that modCount is stale). Only consulted once a point has noticed the
   * chunk's modCount moved, so it is off the common hot path.
   */
  public boolean changedSince(int x, int y, int z, int sinceModCount) {
    Integer stamp = changeStamps.get(positionKey(x, y, z));
    return stamp != null && stamp > sinceModCount;
  }

  private static int positionKey(int x, int y, int z) {
    return (y << 8) | (x << 4) | z;
  }

  private Material[] sectionFor(int y) {
    int sectionY = y >> 4;
    Material[] section = sections.get(sectionY);
    if (section != null) return section;
    Material[] created = new Material[SECTION_VOLUME];
    Material[] existing = sections.putIfAbsent(sectionY, created);
    return existing != null ? existing : created;
  }
}
