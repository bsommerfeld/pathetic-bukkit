package de.bsommerfeld.pathetic.bukkit.context;

import de.bsommerfeld.pathetic.api.pathing.context.EnvironmentContext;
import java.util.UUID;
import lombok.Value;
import org.bukkit.World;

@Value
public class BukkitEnvironmentContext implements EnvironmentContext {

  @Deprecated World world;
  UUID worldUID;
}
