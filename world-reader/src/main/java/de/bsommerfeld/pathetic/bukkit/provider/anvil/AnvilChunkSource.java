package de.bsommerfeld.pathetic.bukkit.provider.anvil;

import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.BlockState;

/**
 * A {@link ChunkBlockSource} backed by chunk data read from disk (an Anvil region file) rather than
 * a live server snapshot. Each 16x16x16 section keeps its palette plus the raw packed index array,
 * and a single block is unpacked on demand — the decoded chunk memoizes the result, so each block is
 * unpacked at most once and sections never touched by the search are never unpacked at all.
 *
 * <p>Carries no {@link ChunkSnapshot}; a {@link BlockState} is instead reconstructed on demand from
 * the palette's block-data string, so processors can read block data for an off-disk chunk exactly
 * as they would for a loaded one.
 */
public final class AnvilChunkSource implements ChunkBlockSource {

  static final String AIR = "minecraft:air";

  /** Sections that exist in the chunk, keyed by section Y ({@code worldY >> 4}). */
  private final Map<Integer, Section> sections;

  public AnvilChunkSource(Map<Integer, Section> sections) {
    this.sections = sections;
  }

  @Override
  public Material getMaterial(int x, int y, int z) {
    Section section = sections.get(y >> 4);
    // A missing section is empty space (e.g. above the surface): treat it as air.
    if (section == null) return Material.AIR;
    return section.materialAt(x, y & 15, z);
  }

  @Override
  public ChunkSnapshot snapshotOrNull() {
    return null;
  }

  @Override
  public BlockState getBlockState(int x, int y, int z) {
    Section section = sections.get(y >> 4);
    String blockData = section == null ? AIR : section.blockDataAt(x, y & 15, z);
    try {
      return Bukkit.createBlockData(blockData).createBlockState();
    } catch (IllegalArgumentException | NullPointerException e) {
      return null; // unknown/modded block data string — caller treats it like a missing point
    }
  }

  /**
   * One 16x16x16 section: parallel palettes of materials and block-data strings, and (unless the
   * whole section is a single block) a tightly packed array of palette indices ({@code bitsPerIndex}
   * bits each, never spanning a long — the post-1.16 layout the modern "sections" schema uses).
   */
  public static final class Section {

    private final Material[] palette;
    private final String[] blockData;
    private final long[] data;
    private final int bitsPerIndex;
    private final int indicesPerLong;

    public Section(Material[] palette, String[] blockData, long[] data, int bitsPerIndex) {
      this.palette = palette;
      this.blockData = blockData;
      this.data = data;
      this.bitsPerIndex = bitsPerIndex;
      this.indicesPerLong = bitsPerIndex == 0 ? 0 : 64 / bitsPerIndex;
    }

    /** Single-block section: no packed data, the whole section is {@code palette[0]}. */
    public static Section uniform(Material material, String blockData) {
      return new Section(new Material[] {material}, new String[] {blockData}, null, 0);
    }

    Material materialAt(int localX, int localY, int localZ) {
      int index = paletteIndexAt(localX, localY, localZ);
      return index < 0 || index >= palette.length ? Material.AIR : palette[index];
    }

    String blockDataAt(int localX, int localY, int localZ) {
      int index = paletteIndexAt(localX, localY, localZ);
      return index < 0 || index >= blockData.length ? AIR : blockData[index];
    }

    private int paletteIndexAt(int localX, int localY, int localZ) {
      if (data == null) return 0;
      int blockIndex = (localY << 8) | (localZ << 4) | localX; // YZX order
      int longIndex = blockIndex / indicesPerLong;
      int bitOffset = (blockIndex % indicesPerLong) * bitsPerIndex;
      long mask = (1L << bitsPerIndex) - 1;
      return (int) ((data[longIndex] >>> bitOffset) & mask);
    }
  }
}
