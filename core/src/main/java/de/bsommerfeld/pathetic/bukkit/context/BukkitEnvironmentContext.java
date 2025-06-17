package de.bsommerfeld.pathetic.bukkit.context;

import de.bsommerfeld.pathetic.api.pathing.context.EnvironmentContext;
import lombok.Value;
import org.bukkit.World;

@Value
public class BukkitEnvironmentContext implements EnvironmentContext {

  World world;
}
