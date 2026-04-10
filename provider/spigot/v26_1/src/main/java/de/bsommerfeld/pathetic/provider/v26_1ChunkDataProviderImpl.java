package de.bsommerfeld.pathetic.provider;

import java.lang.reflect.Field;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftChunk;
import org.bukkit.craftbukkit.CraftWorld;

public class v26_1ChunkDataProviderImpl implements ChunkDataProvider {

  private static final Field blockIDField;

  static {
    try {
      blockIDField = CraftChunk.class.getDeclaredField("emptyBlockIDs");
      blockIDField.setAccessible(true);
    } catch (NoSuchFieldException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public ChunkSnapshot getSnapshot(World world, int chunkX, int chunkZ) {
    try {

      ServerLevel serverLevel = ((CraftWorld) world).getHandle();
      CraftChunk craftChunk = new CraftChunk(serverLevel, chunkX, chunkZ);

      serverLevel.getChunk(chunkX, chunkZ, ChunkStatus.FULL, true);
      PalettedContainer<BlockState> palettedContainer =
          (PalettedContainer<BlockState>) blockIDField.get(craftChunk);

      palettedContainer.acquire();
      ChunkSnapshot chunkSnapshot = craftChunk.getChunkSnapshot();
      palettedContainer.release();

      return chunkSnapshot;

    } catch (IllegalAccessException e) {
      e.printStackTrace();
      return null;
    }
  }

  @Override
  public org.bukkit.block.BlockState getBlockState(ChunkSnapshot snapshot, int x, int y, int z) {
    return snapshot.getBlockData(x, y, z).createBlockState();
  }
}
