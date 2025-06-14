package de.bsommerfeld.pathetic.bukkit.provider;

import de.bsommerfeld.pathetic.api.provider.NavigationPoint;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.block.BlockState;

@Getter
@RequiredArgsConstructor
public class BukkitNavigationPoint implements NavigationPoint {

  private final ChunkSnapshot chunk;
  private final Material material;
  private final BlockState blockState;

  @Override
  public boolean isTraversable() {
    return !material.isSolid();
  }
}
