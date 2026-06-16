package de.bsommerfeld.pathetic.bukkit.provider.anvil;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, allocation-conscious reader for Minecraft's binary NBT format — just enough to walk a
 * chunk's data. Vendored on purpose: it keeps the Anvil reader dependency-free and fully portable
 * across server forks and versions (NBT is self-describing, so the same parser handles every
 * version; the chunk <em>schema</em> on top is what differs).
 *
 * <p>Tags decode to plain Java values so the rest of the reader stays simple: compounds to {@code
 * Map<String,Object>}, lists to {@code List<Object>}, and the array tags to primitive {@code
 * byte[]}/{@code int[]}/{@code long[]} (kept primitive — the long arrays holding packed block
 * indices are the bulk of the data and must not be boxed).
 */
final class BinaryNbt {

  private static final int TAG_END = 0;
  private static final int TAG_BYTE = 1;
  private static final int TAG_SHORT = 2;
  private static final int TAG_INT = 3;
  private static final int TAG_LONG = 4;
  private static final int TAG_FLOAT = 5;
  private static final int TAG_DOUBLE = 6;
  private static final int TAG_BYTE_ARRAY = 7;
  private static final int TAG_STRING = 8;
  private static final int TAG_LIST = 9;
  private static final int TAG_COMPOUND = 10;
  private static final int TAG_INT_ARRAY = 11;
  private static final int TAG_LONG_ARRAY = 12;

  private BinaryNbt() {}

  /**
   * Parses an uncompressed NBT byte array whose root is a (named) compound tag.
   *
   * @return the root compound as a map, or {@code null} if the bytes are not a compound-rooted NBT
   *     document.
   */
  @SuppressWarnings("unchecked")
  static Map<String, Object> read(byte[] bytes) throws IOException {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
      int rootType = in.readUnsignedByte();
      if (rootType != TAG_COMPOUND) return null;
      in.readUTF(); // root name, conventionally empty
      return (Map<String, Object>) readPayload(in, TAG_COMPOUND);
    } catch (EOFException e) {
      return null;
    }
  }

  private static Object readPayload(DataInputStream in, int type) throws IOException {
    switch (type) {
      case TAG_BYTE:
        return in.readByte();
      case TAG_SHORT:
        return in.readShort();
      case TAG_INT:
        return in.readInt();
      case TAG_LONG:
        return in.readLong();
      case TAG_FLOAT:
        return in.readFloat();
      case TAG_DOUBLE:
        return in.readDouble();
      case TAG_BYTE_ARRAY:
        return readByteArray(in);
      case TAG_STRING:
        return in.readUTF();
      case TAG_LIST:
        return readList(in);
      case TAG_COMPOUND:
        return readCompound(in);
      case TAG_INT_ARRAY:
        return readIntArray(in);
      case TAG_LONG_ARRAY:
        return readLongArray(in);
      default:
        throw new IOException("Unknown NBT tag type: " + type);
    }
  }

  private static Map<String, Object> readCompound(DataInputStream in) throws IOException {
    Map<String, Object> map = new HashMap<>();
    while (true) {
      int type = in.readUnsignedByte();
      if (type == TAG_END) return map;
      String name = in.readUTF();
      map.put(name, readPayload(in, type));
    }
  }

  private static List<Object> readList(DataInputStream in) throws IOException {
    int elementType = in.readUnsignedByte();
    int length = in.readInt();
    List<Object> list = new ArrayList<>(Math.max(0, length));
    for (int i = 0; i < length; i++) {
      list.add(readPayload(in, elementType));
    }
    return list;
  }

  private static byte[] readByteArray(DataInputStream in) throws IOException {
    int length = in.readInt();
    byte[] array = new byte[length];
    in.readFully(array);
    return array;
  }

  private static int[] readIntArray(DataInputStream in) throws IOException {
    int length = in.readInt();
    int[] array = new int[length];
    for (int i = 0; i < length; i++) array[i] = in.readInt();
    return array;
  }

  private static long[] readLongArray(DataInputStream in) throws IOException {
    int length = in.readInt();
    long[] array = new long[length];
    for (int i = 0; i < length; i++) array[i] = in.readLong();
    return array;
  }
}
