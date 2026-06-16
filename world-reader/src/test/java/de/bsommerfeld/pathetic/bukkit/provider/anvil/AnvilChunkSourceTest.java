package de.bsommerfeld.pathetic.bukkit.provider.anvil;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * Exercises the palette bit-unpacking in {@link AnvilChunkSource} directly — the riskiest part of
 * the Anvil reader — without any file IO or a running server, by packing a section the same way
 * Minecraft does and asserting the materials come back out.
 */
class AnvilChunkSourceTest {

  private static final int BITS = 4; // palette of 3 -> 4 bits per index (the minimum)
  private static final int INDICES_PER_LONG = 64 / BITS;

  @Test
  void unpacksPackedPaletteIndices() {
    Material[] palette = {Material.AIR, Material.STONE, Material.DIRT};
    String[] blockData = {"minecraft:air", "minecraft:stone", "minecraft:dirt"};
    long[] data = new long[256]; // 4096 entries / 16 per long

    // Place a few known blocks at distinct coordinates.
    set(data, 0, 0, 0, 1); // STONE
    set(data, 15, 0, 0, 2); // DIRT
    set(data, 1, 2, 3, 1); // STONE
    set(data, 15, 15, 15, 2); // DIRT

    Map<Integer, AnvilChunkSource.Section> sections = new HashMap<>();
    sections.put(0, new AnvilChunkSource.Section(palette, blockData, data, BITS));
    AnvilChunkSource source = new AnvilChunkSource(sections);

    assertEquals(Material.STONE, source.getMaterial(0, 0, 0));
    assertEquals(Material.DIRT, source.getMaterial(15, 0, 0));
    assertEquals(Material.STONE, source.getMaterial(1, 2, 3));
    assertEquals(Material.DIRT, source.getMaterial(15, 15, 15));
    assertEquals(Material.AIR, source.getMaterial(5, 5, 5)); // untouched -> palette[0]
  }

  @Test
  void uniformSectionIgnoresCoordinates() {
    Map<Integer, AnvilChunkSource.Section> sections = new HashMap<>();
    sections.put(1, AnvilChunkSource.Section.uniform(Material.STONE, "minecraft:stone"));
    AnvilChunkSource source = new AnvilChunkSource(sections);

    // Section 1 spans world y 16..31.
    assertEquals(Material.STONE, source.getMaterial(0, 16, 0));
    assertEquals(Material.STONE, source.getMaterial(7, 25, 9));
  }

  @Test
  void missingSectionIsAir() {
    AnvilChunkSource source = new AnvilChunkSource(new HashMap<>());
    assertEquals(Material.AIR, source.getMaterial(3, 200, 4));
  }

  @Test
  void handlesNegativeSectionY() {
    Map<Integer, AnvilChunkSource.Section> sections = new HashMap<>();
    sections.put(-1, AnvilChunkSource.Section.uniform(Material.DIRT, "minecraft:dirt"));
    AnvilChunkSource source = new AnvilChunkSource(sections);

    // Section -1 spans world y -16..-1.
    assertEquals(Material.DIRT, source.getMaterial(0, -1, 0));
    assertEquals(Material.DIRT, source.getMaterial(5, -16, 5));
  }

  /** Packs {@code paletteIndex} at the YZX block position, mirroring {@code Section.materialAt}. */
  private static void set(long[] data, int x, int y, int z, int paletteIndex) {
    int blockIndex = (y << 8) | (z << 4) | x;
    int longIndex = blockIndex / INDICES_PER_LONG;
    int bitOffset = (blockIndex % INDICES_PER_LONG) * BITS;
    data[longIndex] |= (long) paletteIndex << bitOffset;
  }
}
