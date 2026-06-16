package de.bsommerfeld.pathetic.example;

import de.bsommerfeld.pathetic.bukkit.ChunkCacheConfiguration;
import de.bsommerfeld.pathetic.bukkit.PatheticBukkit;
import de.bsommerfeld.pathetic.example.command.PatheticCommand;
import de.bsommerfeld.pathetic.example.config.PathfinderManager;
import de.bsommerfeld.pathetic.example.config.PathfinderSettings;
import de.bsommerfeld.pathetic.example.listener.ChunkInvalidateListener;
import java.util.concurrent.TimeUnit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class PatheticPlugin extends JavaPlugin {

  // Called when the plugin is enabled
  @Override
  public void onEnable() {

    /*
     * The whole pathfinder configuration now lives in config.yml instead of being hard-coded.
     *
     * saveDefaultConfig() copies the bundled config.yml on first run; PathfinderSettings then
     * loads every option from it. The PathfinderManager builds the actual Pathfinder from those
     * settings and can rebuild it on the fly - that is what lets "/path config <key> <value>"
     * change the configuration in-game without ever rebuilding the jar.
     */
    saveDefaultConfig();

    // The chunk-cache tuning is applied once at startup, so it is read before initialize().
    PatheticBukkit.initialize(this, readCacheConfiguration(getConfig()));

    PathfinderSettings settings = new PathfinderSettings(this);
    settings.load(getConfig());

    PathfinderManager pathfinderManager = new PathfinderManager(settings);

    // Register the command executors. The command reads the manager's current pathfinder, so it
    // always uses the latest configuration.
    getCommand("pathetic").setExecutor(new PatheticCommand(this, pathfinderManager));

    // Register the ChunkInvalidateListener
    getServer().getPluginManager().registerEvents(new ChunkInvalidateListener(), this);
  }

  // IMPORTANT: release Pathetic's shared threads and caches, or they leak across a reload.
  @Override
  public void onDisable() {
    PatheticBukkit.shutdown();
  }

  private static ChunkCacheConfiguration readCacheConfiguration(FileConfiguration config) {
    ChunkCacheConfiguration.Builder builder =
        ChunkCacheConfiguration.builder()
            .heatDecayInterval(config.getLong("cache.heat-decay-seconds", 60), TimeUnit.SECONDS)
            .maxHeat(config.getInt("cache.max-heat", 5))
            .sweepInterval(config.getLong("cache.sweep-seconds", 60), TimeUnit.SECONDS);

    int maxChunks = config.getInt("cache.max-chunks", 0); // 0 = auto (heap-scaled)
    if (maxChunks > 0) builder.maxCachedChunks(maxChunks);

    builder
        .memoryPressureEviction(config.getBoolean("cache.memory-pressure-eviction", false))
        .minFreeHeapPercent(config.getInt("cache.min-free-heap-percent", 15));

    return builder.build();
  }
}
