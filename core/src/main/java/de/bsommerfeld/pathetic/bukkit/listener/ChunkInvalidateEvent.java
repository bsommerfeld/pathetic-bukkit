package de.bsommerfeld.pathetic.bukkit.listener;

import org.bukkit.block.Block;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Represents an event triggered to indicate that a chunk should be invalidated due to a
 * block-related action or event. This event is typically invoked for updates that may affect
 * navigation or spatial computations within a specific chunk.
 *
 * <p>This event works in conjunction with block-based events such as block placement, breaking,
 * burning, explosions, growth, piston actions, and more, ensuring that any affected chunks are
 * flagged for invalidation.
 *
 * <p><strong>NOTE:</strong> This does NOT account chunk invalidation due to unloading worlds.
 */
public class ChunkInvalidateEvent extends Event implements Cancellable {

  private static final HandlerList HANDLER_LIST = new HandlerList();

  private final Block block;

  private boolean cancelled;

  public ChunkInvalidateEvent(Block block) {
    this.block = block;
  }

  public static HandlerList getHandlerList() {
    return HANDLER_LIST;
  }

  /**
   * @return The block which triggered this chunk invalidation.
   */
  public Block getBlock() {
    return block;
  }

  public HandlerList getHandlers() {
    return HANDLER_LIST;
  }

  @Override
  public boolean isCancelled() {
    return cancelled;
  }

  @Override
  public void setCancelled(boolean cancel) {
    this.cancelled = cancel;
  }
}
