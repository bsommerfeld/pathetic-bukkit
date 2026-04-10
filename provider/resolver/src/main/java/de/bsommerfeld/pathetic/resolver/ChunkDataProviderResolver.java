package de.bsommerfeld.pathetic.resolver;

import de.bsommerfeld.pathetic.provider.ChunkDataProvider;
import de.bsommerfeld.pathetic.provider.paper.PaperChunkDataProvider;
import de.bsommerfeld.pathetic.provider.spigot.SpigotChunkDataProviderResolver;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Getter
@Slf4j
public class ChunkDataProviderResolver {

  private final ChunkDataProvider chunkDataProvider;

  /** For legacy 1.X.Y Minecraft versions. */
  public ChunkDataProviderResolver(int major, int minor) {
    if (isPaper()) {
      chunkDataProvider = new PaperChunkDataProvider();
    } else {
      chunkDataProvider = SpigotChunkDataProviderResolver.resolve(major, minor);
    }
    log.debug("Detected version 1.{}.{}, using {}", major, minor, chunkDataProvider.getClass().getSimpleName());
  }

  /** For calendar-versioned YY.X.Y Minecraft versions. */
  public ChunkDataProviderResolver(int year, int feature, int patch) {
    if (isPaper()) {
      chunkDataProvider = new PaperChunkDataProvider();
    } else {
      chunkDataProvider = SpigotChunkDataProviderResolver.resolve(year, feature, patch);
    }
    log.debug("Detected version {}.{}.{}, using {}", year, feature, patch, chunkDataProvider.getClass().getSimpleName());
  }

  private boolean isPaper() {
    try {
      Class.forName("io.papermc.paper.configuration.GlobalConfiguration");
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }
}
