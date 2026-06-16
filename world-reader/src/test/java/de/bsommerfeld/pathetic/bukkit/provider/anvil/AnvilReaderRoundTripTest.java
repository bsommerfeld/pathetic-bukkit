package de.bsommerfeld.pathetic.bukkit.provider.anvil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end test of the Anvil reader: it writes a chunk to a real {@code r.X.Z.mca} region file the
 * same way the server does (NBT → zlib → sectors + header) and reads it back through {@link
 * RegionFiles}, {@link BinaryNbt} and {@link AnvilChunkLoader}. This pins the on-disk format so the
 * disk fast path can't silently regress.
 */
class AnvilReaderRoundTripTest {

  @TempDir Path worldFolder;

  @Test
  void readsBackAChunkWrittenInTheServerFormat() throws IOException {
    // A section whose palette is [air, stone], with a single stone block at local (0,0,0).
    long[] packed = new long[256]; // 4096 entries at 4 bits = 16 per long
    packed[0] |= 1L; // block index 0 -> palette index 1 (stone)

    Map<String, Object> root = chunkRoot(packed);
    byte[] nbt = writeNbt(root);
    writeRegionFile(worldFolder, 0, 0, nbt);

    // 1) RegionFiles + BinaryNbt recover the structure.
    byte[] readBack = RegionFiles.readChunkNbt(regionFile(worldFolder, 0, 0), 0, 0);
    assertNotNull(readBack, "region read should succeed");
    assertNotNull(BinaryNbt.read(readBack).get("sections"), "sections tag should survive");

    // 2) The full loader (with a world pointing at our temp folder) decodes materials.
    World world = mock(World.class);
    when(world.getWorldFolder()).thenReturn(worldFolder.toFile());
    when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);

    ChunkBlockSource source = AnvilChunkLoader.load(world, 0, 0);
    assertNotNull(source, "loader should produce a source for a present chunk");
    assertEquals(Material.STONE, source.getMaterial(0, 0, 0));
    assertEquals(Material.AIR, source.getMaterial(1, 0, 0));
  }

  @Test
  void missingChunkLoadsAsNull() {
    World world = mock(World.class);
    when(world.getWorldFolder()).thenReturn(worldFolder.toFile());
    when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);

    assertNull(AnvilChunkLoader.load(world, 5, 7), "absent chunk must be a miss, not fabricated");
  }

  private static Map<String, Object> chunkRoot(long[] packedBlockStates) {
    Map<String, Object> paletteAir = new LinkedHashMap<>();
    paletteAir.put("Name", "minecraft:air");
    Map<String, Object> paletteStone = new LinkedHashMap<>();
    paletteStone.put("Name", "minecraft:stone");

    Map<String, Object> blockStates = new LinkedHashMap<>();
    blockStates.put("palette", new ArrayList<>(List.of(paletteAir, paletteStone)));
    blockStates.put("data", packedBlockStates);

    Map<String, Object> section = new LinkedHashMap<>();
    section.put("Y", (byte) 0);
    section.put("block_states", blockStates);

    Map<String, Object> root = new LinkedHashMap<>();
    root.put("DataVersion", 3953); // 1.21
    root.put("sections", new ArrayList<>(List.of(section)));
    return root;
  }

  // --- minimal NBT + region writers (server-compatible) ----------------------------------------

  private static byte[] writeNbt(Map<String, Object> root) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (DataOutputStream out = new DataOutputStream(bytes)) {
      out.writeByte(10); // root compound
      out.writeUTF("");
      writeCompound(out, root);
    }
    return bytes.toByteArray();
  }

  @SuppressWarnings("unchecked")
  private static void writePayload(DataOutputStream out, Object value) throws IOException {
    if (value instanceof Byte) out.writeByte((Byte) value);
    else if (value instanceof Integer) out.writeInt((Integer) value);
    else if (value instanceof Long) out.writeLong((Long) value);
    else if (value instanceof String) out.writeUTF((String) value);
    else if (value instanceof long[]) writeLongArray(out, (long[]) value);
    else if (value instanceof Map) writeCompound(out, (Map<String, Object>) value);
    else if (value instanceof List) writeList(out, (List<Object>) value);
    else throw new IOException("unsupported test NBT value: " + value);
  }

  private static void writeCompound(DataOutputStream out, Map<String, Object> map)
      throws IOException {
    for (Map.Entry<String, Object> entry : map.entrySet()) {
      out.writeByte(typeOf(entry.getValue()));
      out.writeUTF(entry.getKey());
      writePayload(out, entry.getValue());
    }
    out.writeByte(0); // TAG_End
  }

  private static void writeList(DataOutputStream out, List<Object> list) throws IOException {
    int elementType = list.isEmpty() ? 10 : typeOf(list.get(0));
    out.writeByte(elementType);
    out.writeInt(list.size());
    for (Object element : list) writePayload(out, element);
  }

  private static void writeLongArray(DataOutputStream out, long[] array) throws IOException {
    out.writeInt(array.length);
    for (long value : array) out.writeLong(value);
  }

  private static int typeOf(Object value) {
    if (value instanceof Byte) return 1;
    if (value instanceof Integer) return 3;
    if (value instanceof Long) return 4;
    if (value instanceof String) return 8;
    if (value instanceof List) return 9;
    if (value instanceof Map) return 10;
    if (value instanceof long[]) return 12;
    throw new IllegalArgumentException("unsupported test NBT type: " + value);
  }

  private static void writeRegionFile(Path worldFolder, int chunkX, int chunkZ, byte[] nbt)
      throws IOException {
    byte[] compressed = zlib(nbt);
    int length = compressed.length + 1; // compression byte + payload
    int onDisk = 4 + length; // length int + blob
    int sectorCount = (onDisk + 4095) / 4096;

    byte[] file = new byte[8192 + sectorCount * 4096];
    ByteBuffer buffer = ByteBuffer.wrap(file);

    int headerIndex = ((chunkX & 31) + (chunkZ & 31) * 32) * 4;
    buffer.putInt(headerIndex, (2 << 8) | (sectorCount & 0xFF)); // offset 2 sectors, count

    int dataStart = 8192;
    buffer.putInt(dataStart, length);
    file[dataStart + 4] = 2; // zlib compression
    System.arraycopy(compressed, 0, file, dataStart + 5, compressed.length);

    Path region = regionFile(worldFolder, chunkX, chunkZ);
    Files.createDirectories(region.getParent());
    Files.write(region, file);
  }

  private static Path regionFile(Path worldFolder, int chunkX, int chunkZ) {
    return worldFolder
        .resolve("region")
        .resolve("r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
  }

  private static byte[] zlib(byte[] data) {
    Deflater deflater = new Deflater();
    deflater.setInput(data);
    deflater.finish();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    while (!deflater.finished()) {
      int written = deflater.deflate(buffer);
      out.write(buffer, 0, written);
    }
    deflater.end();
    return out.toByteArray();
  }
}
