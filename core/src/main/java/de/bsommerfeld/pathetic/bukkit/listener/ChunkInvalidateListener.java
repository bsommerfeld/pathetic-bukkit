package de.bsommerfeld.pathetic.bukkit.listener;

import de.bsommerfeld.pathetic.bukkit.provider.FailingNavigationPointProvider;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.world.WorldUnloadEvent;

public class ChunkInvalidateListener implements Listener {

  // --- single-block changes: patch just the changed block, keeping the chunk cached -------------
  // MONITOR + ignoreCancelled so we patch only confirmed changes; an in-place patch, unlike a
  // whole-chunk drop, is not self-correcting if the event is cancelled after us.

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onPlace(BlockPlaceEvent event) {
    Block block = event.getBlock();
    handleBlockChange(block, block.getBlockData()); // the block is already the placed one
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBreak(BlockBreakEvent event) {
    handleBlockRemoved(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onBurn(BlockBurnEvent event) {
    handleBlockRemoved(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onDecay(LeavesDecayEvent event) {
    handleBlockRemoved(event.getBlock());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onFade(BlockFadeEvent event) {
    handleBlockChange(event.getBlock(), event.getNewState().getBlockData());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onGrow(BlockGrowEvent event) {
    handleBlockChange(event.getBlock(), event.getNewState().getBlockData());
  }

  // --- multi-block / unknown-result changes: fall back to invalidating the whole chunk ----------

  @EventHandler
  public void onExplode(BlockExplodeEvent event) {
    handleChunk(event.getBlock());
  }

  @EventHandler
  public void onFromTo(BlockFromToEvent event) {
    handleChunk(event.getBlock(), event.getToBlock());
  }

  @EventHandler
  public void onPistonExtend(BlockPistonExtendEvent event) {
    handleChunk(event.getBlock());
  }

  @EventHandler
  public void onPistonRetract(BlockPistonRetractEvent event) {
    handleChunk(event.getBlock());
  }

  @EventHandler
  public void onUnloadWorld(WorldUnloadEvent event) {
    FailingNavigationPointProvider.invalidateAllChunks(event.getWorld().getUID());
  }

  private void handleBlockRemoved(Block block) {
    handleBlockChange(block, Material.AIR.createBlockData());
  }

  private void handleBlockChange(Block block, BlockData newBlockData) {
    if (firesInvalidation(block)) {
      FailingNavigationPointProvider.invalidateBlock(
          block.getWorld().getUID(), block.getX(), block.getY(), block.getZ(), newBlockData);
    }
  }

  private void handleChunk(Block... blocks) {
    for (Block block : blocks) {
      if (firesInvalidation(block)) {
        FailingNavigationPointProvider.invalidateChunk(
            block.getWorld().getUID(), block.getChunk().getX(), block.getChunk().getZ());
      }
    }
  }

  /** Fires the (cancellable) {@link ChunkInvalidateEvent} and reports whether to proceed. */
  private boolean firesInvalidation(Block block) {
    ChunkInvalidateEvent event = new ChunkInvalidateEvent(block);
    Bukkit.getPluginManager().callEvent(event);
    return !event.isCancelled();
  }
}
