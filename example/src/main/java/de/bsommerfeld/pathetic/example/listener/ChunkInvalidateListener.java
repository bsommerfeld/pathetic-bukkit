package de.bsommerfeld.pathetic.example.listener;

import de.bsommerfeld.pathetic.bukkit.listener.ChunkInvalidateEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class ChunkInvalidateListener implements Listener {

  @EventHandler
  public void onChunkInvalidate(ChunkInvalidateEvent event) {
    // Your custom _what to do on invalidation_ logic.
  }
}
