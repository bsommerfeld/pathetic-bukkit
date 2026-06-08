package de.bsommerfeld.pathetic.example;

import de.bsommerfeld.pathetic.api.factory.PathfinderFactory;
import de.bsommerfeld.pathetic.api.pathing.Pathfinder;
import de.bsommerfeld.pathetic.api.pathing.configuration.PathfinderConfiguration;
import de.bsommerfeld.pathetic.api.pathing.heuristic.HeuristicStrategies;
import de.bsommerfeld.pathetic.bukkit.PatheticBukkit;
import de.bsommerfeld.pathetic.bukkit.hook.MetricsHook;
import de.bsommerfeld.pathetic.bukkit.hook.SpigotPathfindingHook;
import de.bsommerfeld.pathetic.bukkit.provider.LoadingNavigationPointProvider;
import de.bsommerfeld.pathetic.engine.factory.AStarPathfinderFactory;
import de.bsommerfeld.pathetic.example.command.PatheticCommand;
import de.bsommerfeld.pathetic.example.listener.ChunkInvalidateListener;
import de.bsommerfeld.pathetic.example.processor.SimpleValidationProcessor;
import java.util.List;
import org.bukkit.plugin.java.JavaPlugin;

public final class PatheticPlugin extends JavaPlugin {

  // Called when the plugin is enabled
  @Override
  public void onEnable() {

    // Initialize Pathetic with this plugin instance
    PatheticBukkit.initialize(this);

    // Create the respective PathfinderFactory
    PathfinderFactory factory = new AStarPathfinderFactory();

    // Create custom configuration for the pathfinder
    // Keep in mind that a provider must always be given
    PathfinderConfiguration configuration =
        PathfinderConfiguration.builder()
            .provider(new LoadingNavigationPointProvider()) // For loading chunks
            .fallback(true) // Allow fallback strategies if the primary fails
            .validationProcessors(List.of(new SimpleValidationProcessor()))
            .async(true)

            // SQUARED is more performant, but less accurate. For "accurate as fuck" use LINEAR
            .heuristicStrategy(HeuristicStrategies.SQUARED)

            /*
             * You can register PathfindingHooks via the configuration.
             * For example, Spigot NEEDS SpigotPathfindingHook to work asynchronously!
             *
             * The MetricsHook is an opt-in hook in order to help with development
             * by providing generic data to https://bstats.org/plugin/bukkit/pathetic-bukkit/29080
             */
            .pathfindingHooks(List.of(new SpigotPathfindingHook(), new MetricsHook()))

            // a higher count allows for more freedom, but also increases
            // computation / wait-time for failure
            .maxIterations(1_000_000)
            .build();

    // There are many more options inside the configuration which are not covered here.
    // Not all options are useful for everyone, and I would advise, to keep your fingers away
    // From options you can't assign. There are always good default values set!

    // Create the pathfinding instance with the factory from the configuration.
    Pathfinder reusablePathfinder = factory.createPathfinder(configuration);

    // Register the command executors
    getCommand("pathetic").setExecutor(new PatheticCommand(reusablePathfinder));

    // Register the ChunkInvalidateListener
    getServer().getPluginManager().registerEvents(new ChunkInvalidateListener(), this);
  }
}
