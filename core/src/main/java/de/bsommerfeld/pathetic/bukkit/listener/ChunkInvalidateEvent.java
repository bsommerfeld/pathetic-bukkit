package de.bsommerfeld.pathetic.bukkit.listener;

import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Represents an event triggered to indicate that a chunk should be invalidated
 * due to a block-related action or event. This event is typically invoked for
 * updates that may affect navigation or spatial computations within a specific
 * chunk.
 *
 * This event works in conjunction with block-based events such as block
 * placement, breaking, burning, explosions, growth, piston actions, and more,
 * ensuring that any affected chunks are flagged for invalidation.
 */
public class ChunkInvalidateEvent extends Event {

  private static final HandlerList HANDLER_LIST = new HandlerList();

  private final Block block;

  public ChunkInvalidateEvent(Block block) {
    this.block = block;
  }

  public static HandlerList getHandlerList() {
    return HANDLER_LIST;
  }

  public Block getBlock() {
    return block;
  }

  public HandlerList getHandlers() {
    return HANDLER_LIST;
  }
}
