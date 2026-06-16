package de.bsommerfeld.pathetic.bukkit.provider.anvil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads a single chunk's uncompressed NBT bytes out of an Anvil region file ({@code r.X.Z.mca})
 * without involving the server's chunk system. The region format is a 8 KiB header (a 4 KiB table
 * of 1024 {@code (sectorOffset, sectorCount)} entries followed by 4 KiB of timestamps) over a body
 * of 4 KiB sectors; each present chunk is a {@code [int length][byte compression][payload]} blob.
 *
 * <p>Open read-only channels are pooled and reused: a long corridor crosses many chunks in the same
 * region file (up to 32x32), so opening the file once per region instead of once per chunk removes a
 * pile of syscalls. Positional reads are concurrency-safe, so a pooled channel is shared freely.
 *
 * <p>Everything here is read-only and best-effort: the server owns these files and may be writing
 * them during auto-save, so any malformed/partial read returns {@code null} and the caller falls
 * back to loading the chunk through the server. This is the portability core of the disk fast path —
 * pure file IO, no NMS.
 */
final class RegionFiles {

  private static final int SECTOR_BYTES = 4096;
  private static final int COMPRESSION_GZIP = 1;
  private static final int COMPRESSION_ZLIB = 2;
  private static final int COMPRESSION_NONE = 3;
  /** High bit on the compression byte means the chunk is stored externally in an {@code .mcc}. */
  private static final int EXTERNAL_FLAG = 0x80;

  /** Most distinct region files kept open at once; a search spans only a handful. */
  private static final int MAX_OPEN_CHANNELS = 16;

  private static final Map<Path, FileChannel> CHANNELS =
      new LinkedHashMap<Path, FileChannel>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, FileChannel> eldest) {
          if (size() <= MAX_OPEN_CHANNELS) return false;
          closeQuietly(eldest.getValue());
          return true;
        }
      };

  private RegionFiles() {}

  /**
   * @return the chunk's decompressed NBT bytes, or {@code null} if the region file or chunk is
   *     absent, externally stored, uses an unsupported compression, or could not be read cleanly.
   */
  static byte[] readChunkNbt(Path regionFile, int chunkX, int chunkZ) {
    FileChannel channel = acquire(regionFile);
    if (channel == null) return null;
    try {
      long fileSize = channel.size();
      if (fileSize < SECTOR_BYTES * 2L) return null;

      int headerIndex = ((chunkX & 31) + (chunkZ & 31) * 32) * 4;
      int location = readInt(channel, headerIndex);
      if (location == 0) return null; // chunk not present in this region

      int sectorOffset = (location >>> 8) & 0xFFFFFF;
      int sectorCount = location & 0xFF;
      if (sectorOffset < 2 || sectorCount == 0) return null;

      long chunkStart = (long) sectorOffset * SECTOR_BYTES;
      if (chunkStart + 5 > fileSize) return null;

      ByteBuffer prefix = read(channel, chunkStart, 5);
      if (prefix == null) return null;
      int length = prefix.getInt();
      int compression = prefix.get() & 0xFF;
      if (length <= 1) return null;
      if ((compression & EXTERNAL_FLAG) != 0) return null; // .mcc oversized chunk, unsupported

      int dataLength = length - 1;
      if (chunkStart + 5 + dataLength > fileSize) return null;

      ByteBuffer data = read(channel, chunkStart + 5, dataLength);
      if (data == null) return null;
      byte[] raw = new byte[dataLength];
      data.get(raw);
      return decompress(compression, raw);
    } catch (IOException e) {
      return null; // includes the channel being closed by eviction mid-read; caller falls back
    }
  }

  /** Closes and forgets all pooled channels. Called on plugin shutdown. */
  static void clearCache() {
    synchronized (CHANNELS) {
      for (FileChannel channel : CHANNELS.values()) closeQuietly(channel);
      CHANNELS.clear();
    }
  }

  private static FileChannel acquire(Path regionFile) {
    synchronized (CHANNELS) {
      FileChannel channel = CHANNELS.get(regionFile);
      if (channel != null && channel.isOpen()) return channel;
      if (channel != null) CHANNELS.remove(regionFile); // stale/closed entry

      if (!Files.isReadable(regionFile)) return null;
      try {
        FileChannel opened = FileChannel.open(regionFile, StandardOpenOption.READ);
        CHANNELS.put(regionFile, opened);
        return opened;
      } catch (IOException e) {
        return null;
      }
    }
  }

  private static void closeQuietly(FileChannel channel) {
    try {
      channel.close();
    } catch (IOException ignored) {
      // best-effort
    }
  }

  private static byte[] decompress(int compression, byte[] raw) throws IOException {
    switch (compression) {
      case COMPRESSION_NONE:
        return raw;
      case COMPRESSION_GZIP:
        return readAll(new GZIPInputStream(new ByteArrayInputStream(raw)));
      case COMPRESSION_ZLIB:
        return readAll(new InflaterInputStream(new ByteArrayInputStream(raw)));
      default:
        return null; // e.g. LZ4 (type 4) — unsupported, fall back to the server
    }
  }

  private static byte[] readAll(InputStream in) throws IOException {
    try (InputStream stream = in) {
      ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
      byte[] buffer = new byte[16 * 1024];
      int read;
      while ((read = stream.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    }
  }

  private static int readInt(FileChannel channel, long position) throws IOException {
    ByteBuffer buffer = read(channel, position, 4);
    return buffer == null ? 0 : buffer.getInt();
  }

  /** Reads exactly {@code length} bytes at {@code position}, or returns null on a short read. */
  private static ByteBuffer read(FileChannel channel, long position, int length) throws IOException {
    ByteBuffer buffer = ByteBuffer.allocate(length);
    long offset = position;
    while (buffer.hasRemaining()) {
      int read = channel.read(buffer, offset);
      if (read < 0) return null;
      offset += read;
    }
    buffer.flip();
    return buffer;
  }
}
