package de.bsommerfeld.pathetic.bukkit;

import de.bsommerfeld.pathetic.bukkit.listener.ChunkInvalidateListener;
import de.bsommerfeld.pathetic.bukkit.provider.FailingNavigationPointProvider;
import de.bsommerfeld.pathetic.bukkit.provider.anvil.AnvilChunkLoader;
import de.bsommerfeld.pathetic.bukkit.provider.world.CacheSweeper;
import de.bsommerfeld.pathetic.bukkit.provider.world.ChunkPrefetcher;
import de.bsommerfeld.pathetic.bukkit.util.BStatsUtil;
import de.bsommerfeld.pathetic.bukkit.util.BukkitVersionUtil;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

@Slf4j
@UtilityClass
public class PatheticBukkit {

  private static final int B_STATS_ID = 29080;

  private static JavaPlugin instance;
  private static Metrics metrics;
  private static ChunkInvalidateListener chunkInvalidateListener;

  /**
   * Initializes Pathetic with the default chunk-cache configuration.
   *
   * <p><strong>You must call {@link #shutdown()} from your plugin's {@code onDisable}.</strong>
   * Pathetic holds JVM-lifetime shared state — background threads and a static chunk cache — that
   * are <em>not</em> tied to any individual {@code Pathfinder}; without {@code shutdown()} they leak
   * across a plugin reload.
   *
   * @throws IllegalStateException If an attempt is made to initialize more than 1 time
   */
  public static void initialize(JavaPlugin javaPlugin) {
    initialize(javaPlugin, ChunkCacheConfiguration.defaults());
  }

  /**
   * Initializes Pathetic with a custom chunk-cache configuration (see {@link
   * ChunkCacheConfiguration}). The same {@link #shutdown()} requirement as {@link
   * #initialize(JavaPlugin)} applies.
   *
   * @throws IllegalStateException If an attempt is made to initialize more than 1 time
   */
  public static void initialize(JavaPlugin javaPlugin, ChunkCacheConfiguration cacheConfiguration) {

    if (instance != null) throw new IllegalStateException("Can't be initialized twice");

    instance = javaPlugin;
    ChunkCacheConfiguration.apply(cacheConfiguration);
    if (cacheConfiguration.prefetchExecutor() != null) {
      ChunkPrefetcher.useExecutor(cacheConfiguration.prefetchExecutor());
    }

    chunkInvalidateListener = new ChunkInvalidateListener();
    Bukkit.getPluginManager().registerEvents(chunkInvalidateListener, javaPlugin);

    initializeBStats(javaPlugin);

    if (BukkitVersionUtil.isLegacyVersion()) {
      BukkitVersionUtil.Version version = BukkitVersionUtil.getVersion();
      if (version.isUnder(16, 0) || version.isEqual(BukkitVersionUtil.Version.of(16, 0))) {
        log.warn(
            "Pathetic is currently running in a version older than or equal to 1.16. "
                + "Some functionalities might not be accessible, such as accessing the BlockState of blocks.");
      }
    }

    FailingNavigationPointProvider.prewarm();
    CacheSweeper.start(
        FailingNavigationPointProvider::sweepCaches, cacheConfiguration.sweepIntervalMs());

    log.debug("Pathetic initialized");
  }

  /**
   * Releases all plugin-scoped resources allocated by {@link #initialize(JavaPlugin)}.
   * <p>
   * Safe to call from a plugin's onDisable hook or before reloading an isolated
   * classloader. After this call, {@link #isInitialized()} returns false and
   * {@link #initialize(JavaPlugin)} can be invoked again.
   */
  public static void shutdown() {
    if (instance == null) return;

    if (metrics != null) {
      metrics.shutdown();
      metrics = null;
    }

    if (chunkInvalidateListener != null) {
      HandlerList.unregisterAll(chunkInvalidateListener);
      chunkInvalidateListener = null;
    }

    CacheSweeper.shutdown();
    ChunkPrefetcher.shutdown();
    AnvilChunkLoader.shutdown();
    FailingNavigationPointProvider.clearAll();

    instance = null;
  }

  @Deprecated
  public static boolean isInitialized() {
    return instance != null;
  }

  @Deprecated
  public static JavaPlugin getPluginInstance() {
    return instance;
  }

  private static void initializeBStats(Plugin javaPlugin) {
    metrics = new Metrics(javaPlugin, B_STATS_ID);
    metrics.addCustomChart(
        new SingleLineChart("pathfinding_steps", BStatsUtil::getPathfindingSteps));
  }
}
