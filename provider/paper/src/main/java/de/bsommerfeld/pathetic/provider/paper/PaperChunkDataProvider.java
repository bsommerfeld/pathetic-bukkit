package de.bsommerfeld.pathetic.provider.paper;

import de.bsommerfeld.pathetic.provider.ChunkDataProvider;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.BlockState;

public class PaperChunkDataProvider implements ChunkDataProvider {

  /**
   * {@code World#getChunkAtIfLoadedImmediately(int, int)} exists on Paper 1.13.2 through 1.21.x
   * but was removed in 26.x, so it is resolved reflectively to keep this artifact working across
   * versions. When available it returns already-loaded chunks from any thread, avoiding the
   * main-thread round trip that {@code getChunkAtAsyncUrgently().join()} costs per chunk.
   */
  private static final MethodHandle GET_CHUNK_AT_IF_LOADED_IMMEDIATELY =
      resolveGetChunkAtIfLoadedImmediately();

  private static MethodHandle resolveGetChunkAtIfLoadedImmediately() {
    try {
      return MethodHandles.publicLookup()
          .findVirtual(
              World.class,
              "getChunkAtIfLoadedImmediately",
              MethodType.methodType(Chunk.class, int.class, int.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      return null;
    }
  }

  @Override
  public ChunkSnapshot getSnapshot(World world, int chunkX, int chunkZ) {
    if (Bukkit.isPrimaryThread()) {
      return world.getChunkAt(chunkX, chunkZ).getChunkSnapshot();
    }

    Chunk loaded = getChunkIfLoadedImmediately(world, chunkX, chunkZ);
    if (loaded != null) {
      return loaded.getChunkSnapshot();
    }

    // thenApply runs on the thread completing the load, so the snapshot is taken
    // while the chunk is safely owned by the server instead of on the joining thread.
    return world
        .getChunkAtAsyncUrgently(chunkX, chunkZ)
        .thenApply(Chunk::getChunkSnapshot)
        .join();
  }

  private static Chunk getChunkIfLoadedImmediately(World world, int chunkX, int chunkZ) {
    if (GET_CHUNK_AT_IF_LOADED_IMMEDIATELY == null) return null;
    try {
      return (Chunk) GET_CHUNK_AT_IF_LOADED_IMMEDIATELY.invokeExact(world, chunkX, chunkZ);
    } catch (Throwable t) {
      return null;
    }
  }

  @Override
  public BlockState getBlockState(ChunkSnapshot snapshot, int x, int y, int z) {
    return snapshot.getBlockData(x, y, z).createBlockState();
  }
}
