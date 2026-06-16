package de.bsommerfeld.pathetic.bukkit.provider.anvil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.bukkit.Material;
import org.bukkit.World;

/**
 * Builds a {@link ChunkBlockSource} for a chunk by reading it straight from the world's region files
 * on disk, bypassing the server's chunk-load/generate machinery. This is the fast path for chunks
 * that are generated and saved but not currently loaded — the common cause of long-distance
 * pathfinding stalls.
 *
 * <p>Portable by construction: pure file IO + NBT, no NMS, so the same code runs on every server
 * fork. Only the modern "sections" chunk schema (Minecraft 1.18+, where sections live at the NBT
 * root and block states use the non-spanning packed layout) is decoded; older schemas and anything
 * unreadable return {@code null} so the caller falls back to the server. Crucially it never
 * fabricates data: an absent or unparseable chunk is a miss, not empty space.
 */
@Slf4j
public final class AnvilChunkLoader {

  /** Cache of namespaced block name to Bukkit {@link Material}; the lookup is otherwise repeated. */
  private static final Map<String, Material> MATERIAL_CACHE = new ConcurrentHashMap<>();

  private AnvilChunkLoader() {}

  /** Forces class loading of the disk-reader internals so the first real search doesn't pay it. */
  public static void prewarm() {
    @SuppressWarnings("unused")
    Class<?>[] warm = {BinaryNbt.class, RegionFiles.class, AnvilChunkSource.class};
  }

  /** Releases pooled region-file channels. Call on plugin shutdown. */
  public static void shutdown() {
    RegionFiles.clearCache();
  }

  /**
   * @return a disk-backed source for the chunk, or {@code null} if the chunk is not present on disk
   *     or is stored in an unsupported format.
   */
  public static ChunkBlockSource load(World world, int chunkX, int chunkZ) {
    Path regionFile =
        regionDirectory(world)
            .resolve("r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");

    byte[] nbt = RegionFiles.readChunkNbt(regionFile, chunkX, chunkZ);
    if (nbt == null) return null;

    try {
      Map<String, Object> root = BinaryNbt.read(nbt);
      if (root == null) return null;

      Object sections = root.get("sections");
      if (!(sections instanceof List)) return null; // not the modern schema (or no block data)

      return buildSource((List<?>) sections);
    } catch (IOException | RuntimeException e) {
      return null; // malformed/partial read mid-save: treat as a miss
    }
  }

  private static ChunkBlockSource buildSource(List<?> sectionTags) {
    Map<Integer, AnvilChunkSource.Section> sections = new HashMap<>();

    for (Object element : sectionTags) {
      if (!(element instanceof Map)) continue;
      Map<?, ?> sectionTag = (Map<?, ?>) element;

      Object yTag = sectionTag.get("Y");
      Object blockStatesTag = sectionTag.get("block_states");
      if (!(yTag instanceof Number) || !(blockStatesTag instanceof Map)) continue;

      AnvilChunkSource.Section section = buildSection((Map<?, ?>) blockStatesTag);
      if (section != null) sections.put(((Number) yTag).intValue(), section);
    }

    if (sections.isEmpty()) return null;
    return new AnvilChunkSource(sections);
  }

  private static AnvilChunkSource.Section buildSection(Map<?, ?> blockStates) {
    Object paletteTag = blockStates.get("palette");
    if (!(paletteTag instanceof List)) return null;
    List<?> paletteList = (List<?>) paletteTag;
    if (paletteList.isEmpty()) return null;

    Material[] palette = new Material[paletteList.size()];
    String[] blockData = new String[paletteList.size()];
    for (int i = 0; i < palette.length; i++) {
      Object entry = paletteList.get(i);
      palette[i] = materialOf(entry);
      blockData[i] = blockDataOf(entry);
    }

    if (palette.length == 1) {
      return AnvilChunkSource.Section.uniform(palette[0], blockData[0]);
    }

    Object dataTag = blockStates.get("data");
    if (!(dataTag instanceof long[])) {
      // A multi-entry palette must come with packed data; without it the section is unusable.
      return null;
    }
    long[] data = (long[]) dataTag;

    int bitsPerIndex = bitsPerIndex(data.length, palette.length);
    if (bitsPerIndex <= 0) return null;
    return new AnvilChunkSource.Section(palette, blockData, data, bitsPerIndex);
  }

  private static Material materialOf(Object paletteEntry) {
    if (!(paletteEntry instanceof Map)) return Material.AIR;
    Object name = ((Map<?, ?>) paletteEntry).get("Name");
    if (!(name instanceof String)) return Material.AIR;
    return MATERIAL_CACHE.computeIfAbsent((String) name, AnvilChunkLoader::resolveMaterial);
  }

  /**
   * Rebuilds the namespaced block-data string (e.g. {@code minecraft:oak_stairs[facing=east]}) from
   * a palette entry's {@code Name} and {@code Properties}, so a disk-backed point can hand a
   * processor the same block data a loaded chunk would via {@code Bukkit.createBlockData}.
   */
  private static String blockDataOf(Object paletteEntry) {
    if (!(paletteEntry instanceof Map)) return AnvilChunkSource.AIR;
    Map<?, ?> entry = (Map<?, ?>) paletteEntry;
    Object name = entry.get("Name");
    if (!(name instanceof String)) return AnvilChunkSource.AIR;

    Object properties = entry.get("Properties");
    if (!(properties instanceof Map) || ((Map<?, ?>) properties).isEmpty()) {
      return (String) name;
    }

    StringBuilder builder = new StringBuilder((String) name).append('[');
    boolean first = true;
    for (Map.Entry<?, ?> property : ((Map<?, ?>) properties).entrySet()) {
      if (!first) builder.append(',');
      builder.append(property.getKey()).append('=').append(property.getValue());
      first = false;
    }
    return builder.append(']').toString();
  }

  private static Material resolveMaterial(String name) {
    Material material = Material.matchMaterial(name);
    if (material != null) return material;
    // Unknown/technical/modded blocks pathfinding can't classify: treat as air (traversable)
    // rather than guessing solid, so they never spuriously wall off a route. Logged once per name
    // (this runs inside computeIfAbsent) so such blocks can be noticed if a path runs through one.
    log.debug("Anvil reader: unmapped block '{}', treating as AIR (traversable)", name);
    return Material.AIR;
  }

  /**
   * Recovers the bits-per-index from the packed array's length, which is the authoritative source
   * (the modern layout never spans a long, so a given length corresponds to exactly one width).
   * Falls back to the standard {@code max(4, ceil(log2(paletteSize)))} only if no width matches.
   */
  private static int bitsPerIndex(int dataLength, int paletteSize) {
    for (int bits = 4; bits <= 31; bits++) {
      int indicesPerLong = 64 / bits;
      int longsNeeded = (4096 + indicesPerLong - 1) / indicesPerLong;
      if (longsNeeded == dataLength) return bits;
    }
    int needed = 32 - Integer.numberOfLeadingZeros(Math.max(1, paletteSize - 1));
    return Math.max(4, needed);
  }

  private static Path regionDirectory(World world) {
    Path worldFolder = world.getWorldFolder().toPath();
    switch (world.getEnvironment()) {
      case NETHER:
        return worldFolder.resolve("DIM-1").resolve("region");
      case THE_END:
        return worldFolder.resolve("DIM1").resolve("region");
      default:
        return worldFolder.resolve("region");
    }
  }
}
