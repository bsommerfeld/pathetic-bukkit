package de.bsommerfeld.pathetic.bukkit.mapper;

import java.util.Arrays;

import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.Vector;
import de.bsommerfeld.pathetic.api.wrapper.PathEnvironment;
import de.bsommerfeld.pathetic.api.wrapper.PathPosition;
import de.bsommerfeld.pathetic.api.wrapper.PathVector;
import de.bsommerfeld.pathetic.engine.util.ErrorLogger;

@UtilityClass
public class BukkitMapper {

  private final boolean IS_NEWER_WORLD;

  static {
    IS_NEWER_WORLD =
            Arrays.stream(World.class.getMethods())
                    .anyMatch(method -> "getMinHeight".equalsIgnoreCase(method.getName()));
  }

  /**
   * @deprecated PathEnvironment is deprecated. Use {@link #toLocation(PathPosition, World)} instead.
   */
  @Deprecated
  @NonNull
  public Location toLocation(@NonNull PathPosition pathPosition) {
    return new Location(
            toWorld(pathPosition.getPathEnvironment()),
            pathPosition.getX(),
            pathPosition.getY(),
            pathPosition.getZ());
  }

  /**
   * @deprecated PathEnvironment is deprecated. Use World directly instead.
   */
  @Deprecated
  public World toWorld(@NonNull PathEnvironment pathEnvironment) {
    return Bukkit.getWorld(pathEnvironment.getUuid());
  }

  /**
   * @deprecated PathEnvironment is deprecated. Use World directly instead.
   */
  @Deprecated
  @NonNull
  public PathEnvironment toPathWorld(@NonNull World world) {
    return new PathEnvironment(
            world.getUID(), world.getName(), getMinHeight(world), getMaxHeight(world));
  }

  /**
   * Converts a PathPosition to a Bukkit Location using the provided World.
   *
   * @param pathPosition the PathPosition to convert
   * @param world the World to use for the Location
   * @return the converted Location
   */
  @NonNull
  public Location toLocation(@NonNull PathPosition pathPosition, @NonNull World world) {
    return new Location(
            world,
            pathPosition.getX(),
            pathPosition.getY(),
            pathPosition.getZ());
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
    return new PathPosition(x, y, z);
  }

  @NonNull
  public Vector toVector(@NonNull PathVector pathVector) {
    return new Vector(pathVector.getX(), pathVector.getY(), pathVector.getZ());
  }

  @NonNull
  public PathVector toPathVector(@NonNull Vector vector) {
    return new PathVector(vector.getX(), vector.getY(), vector.getZ());
  }

  private int getMinHeight(@NonNull World world) {
    return IS_NEWER_WORLD ? world.getMinHeight() : 0;
  }

  private int getMaxHeight(@NonNull World world) {
    return IS_NEWER_WORLD ? world.getMaxHeight() : 256;
  }
}