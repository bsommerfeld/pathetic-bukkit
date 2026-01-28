package de.bsommerfeld.pathetic.bukkit.mapper;

import de.bsommerfeld.pathetic.api.wrapper.PathPosition;
import de.bsommerfeld.pathetic.api.wrapper.PathVector;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;

@UtilityClass
public class BukkitMapper {

  /**
   * Converts a PathPosition to a Bukkit Location using the provided World.
   *
   * @param pathPosition the PathPosition to convert
   * @param world the World to use for the Location
   * @return the converted Location
   */
  @NonNull
  public Location toLocation(@NonNull PathPosition pathPosition, @NonNull World world) {
    return new Location(world, pathPosition.getX(), pathPosition.getY(), pathPosition.getZ());
  }

  /**
   * Converts a Bukkit Location to a PathPosition.
   *
   * @param location the Location to convert
   * @return the converted PathPosition
   */
  @NonNull
  public PathPosition toPathPosition(@NonNull Location location) {
    return createPathPosition(location.getX(), location.getY(), location.getZ());
  }

  /**
   * Creates a PathPosition with World context for the new API.
   *
   * @param x the x coordinate
   * @param y the y coordinate
   * @param z the z coordinate
   */
  @NonNull
  public PathPosition createPathPosition(double x, double y, double z) {
    return PathPosition.of(x, y, z);
  }

  @NonNull
  public Vector toVector(@NonNull PathVector pathVector) {
    return new Vector(pathVector.getX(), pathVector.getY(), pathVector.getZ());
  }

  @NonNull
  public PathVector toPathVector(@NonNull Vector vector) {
    return PathVector.of(vector.getX(), vector.getY(), vector.getZ());
  }
}
