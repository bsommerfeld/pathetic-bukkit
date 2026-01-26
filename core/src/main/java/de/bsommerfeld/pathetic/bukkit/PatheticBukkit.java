package de.bsommerfeld.pathetic.bukkit;

import de.bsommerfeld.pathetic.bukkit.listener.ChunkInvalidateListener;
import de.bsommerfeld.pathetic.bukkit.util.BStatsUtil;
import de.bsommerfeld.pathetic.bukkit.util.BukkitVersionUtil;
import de.bsommerfeld.pathetic.engine.Pathetic;
import de.bsommerfeld.pathetic.engine.util.ErrorLogger;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

@Slf4j
@UtilityClass
public class PatheticBukkit {

  private static int BSTATS_ID = 29080;

  private static JavaPlugin instance;

  /**
   * @throws IllegalStateException If an attempt is made to initialize more than 1 time
   */
  public static void initialize(JavaPlugin javaPlugin) {

    if (instance != null) throw ErrorLogger.logFatalError("Can't be initialized twice");

    instance = javaPlugin;
    Bukkit.getPluginManager().registerEvents(new ChunkInvalidateListener(), javaPlugin);

    initializeBStats(javaPlugin);

    if (BukkitVersionUtil.getVersion().isUnder(16, 0)
        || BukkitVersionUtil.getVersion().isEqual(BukkitVersionUtil.Version.of(16, 0))) {
      log.warn(
          "Pathetic is currently running in a version older than or equal to 1.16. "
              + "Some functionalities might not be accessible, such as accessing the BlockState of blocks.");
    }

    log.debug("Pathetic v{} initialized", Pathetic.getOrLoadEngineVersion());
  }

  public static boolean isInitialized() {
    return instance != null;
  }

  public static JavaPlugin getPluginInstance() {
    return instance;
  }

  private static void initializeBStats(Plugin javaPlugin) {
    Metrics metrics = new Metrics(javaPlugin, BSTATS_ID);
    metrics.addCustomChart(new SimplePie("pathetic_version", Pathetic::getOrLoadEngineVersion));
    metrics.addCustomChart(
        new SingleLineChart("pathfinding_steps", BStatsUtil::getPathfindingSteps));
  }
}
