package de.bsommerfeld.pathetic.example.command;

import de.bsommerfeld.pathetic.api.pathing.Pathfinder;
import de.bsommerfeld.pathetic.api.pathing.PathfindingSearch;
import de.bsommerfeld.pathetic.api.pathing.result.PathfinderResult;
import de.bsommerfeld.pathetic.api.wrapper.PathPosition;
import de.bsommerfeld.pathetic.bukkit.context.BukkitEnvironmentContext;
import de.bsommerfeld.pathetic.bukkit.mapper.BukkitMapper;
import de.bsommerfeld.pathetic.example.config.PathfinderManager;
import de.bsommerfeld.pathetic.example.config.PathfinderSettings;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class PatheticCommand implements TabExecutor {

  // Map to store player sessions using their unique IDs
  private static final Map<UUID, PlayerSession> SESSION_MAP = new HashMap<>();

  // Map to store running swarm benchmarks per player
  private static final Map<UUID, SwarmSession> SWARM_MAP = new HashMap<>();

  // Plugin instance, needed to schedule the swarm ticker
  private final JavaPlugin plugin;

  // Owns the live, reconfigurable pathfinder and its settings
  private final PathfinderManager pathfinderManager;

  // Constructor to initialize the pathfinder manager
  public PatheticCommand(JavaPlugin plugin, PathfinderManager pathfinderManager) {
    this.plugin = plugin;
    this.pathfinderManager = pathfinderManager;
  }

  // Handle command execution
  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

    // Ensure the sender is a player
    if (!(sender instanceof Player)) return false;

    // Ensure at least the sub-command is given
    if (args.length == 0) {
      sender.sendMessage("Usage: /path <pos1|pos2|start|swarm|config>");
      return false;
    }

    // Cast sender to Player
    Player player = (Player) sender;

    // "/path config ..." manages the runtime configuration and takes a variable number of
    // arguments, so it is handled before the single-argument sub-commands below.
    if (args[0].equalsIgnoreCase("config")) {
      handleConfig(player, args);
      return true;
    }

    // The remaining sub-commands take exactly one argument
    if (args.length != 1) return false;

    // Always read the manager's current pathfinder, so live config changes apply.
    Pathfinder pathfinder = pathfinderManager.pathfinder();

    // Retrieve or create a new player session
    PlayerSession playerSession =
        SESSION_MAP.computeIfAbsent(player.getUniqueId(), k -> new PlayerSession());

    // Handle different commands
    switch (args[0]) {
      case "pos1":
        // Set position 1 to the player's current location
        playerSession.setPos1(player.getLocation());
        player.sendMessage("Position 1 set to " + player.getLocation());
        break;

      case "pos2":
        // Set position 2 to the player's current location
        playerSession.setPos2(player.getLocation());
        player.sendMessage("Position 2 set to " + player.getLocation());
        break;

      case "start":
        // Ensure both positions are set before starting pathfinding
        if (!playerSession.isComplete()) {
          player.sendMessage("Set both positions first!");
          return false;
        }

        // Convert Bukkit locations to pathfinding positions
        PathPosition start = BukkitMapper.toPathPosition(playerSession.getPos1());
        PathPosition target = BukkitMapper.toPathPosition(playerSession.getPos2());

        // Inform the player that pathfinding is starting
        player.sendMessage("Starting pathfinding... [Distance: " + start.distance(target) + "]");

        final long startStamp = System.nanoTime();

        /*
         * Initiate pathfinding with the start and target positions. This is where the magic happens
         * and where all the configuration stuff from config.yml does its job.
         *
         * Since 5.1.0: Here we have to give the findPath method a new BukkitEnvironmentContext,
         * which effectively gives the Pathfinder information about the world (or THE world).
         */
        PathfindingSearch pathfindingSearch =
            pathfinder.findPath(start, target, new BukkitEnvironmentContext(player.getWorld()));

        // Handle the pathfinding result
        pathfindingSearch
            .ifPresent(
                result -> {
                  final long endStamp = System.nanoTime();
                  final long timeMs = (endStamp - startStamp) / 1_000_000;

                  player.sendMessage("Time elapsed: " + timeMs + "ms");
                  player.sendMessage("State: " + result.getPathState().name());
                  player.sendMessage("Path length: " + result.getPath().length());

                  result
                      .getPath()
                      .forEach(
                          position -> {
                            Location location =
                                BukkitMapper.toLocation(position, player.getWorld());
                            player.sendBlockChange(
                                location, Material.YELLOW_STAINED_GLASS.createBlockData());
                          });
                })
            .orElse(
                result -> {
                  player.sendMessage("Path not found! Result: " + result.getPathState());
                })
            .exceptionally(
                ex -> System.err.println("Pathfinding operation ended exceptionally: " + ex));

        // If you need to abort the pathfinding, you can cancel the search by calling
        // pathfindingSearch.abort();

        break;

      case "swarm":
        // Toggle: a second /path swarm stops the running benchmark
        SwarmSession runningSwarm = SWARM_MAP.remove(player.getUniqueId());
        if (runningSwarm != null) {
          runningSwarm.stop();
          break;
        }

        SwarmSession swarmSession = new SwarmSession(plugin, pathfinderManager, player);
        SWARM_MAP.put(player.getUniqueId(), swarmSession);
        swarmSession.start();

        player.sendMessage(
            "Swarm started: "
                + SwarmSession.PATHS_PER_SECOND
                + " paths/s around you. Run /path swarm again to stop.");
        break;

      default:
        player.sendMessage("Invalid argument!");
        return false;
    }

    return false;
  }

  /**
   * Handles {@code /path config}: lists options, reloads config.yml, or sets a single value. Every
   * change is saved to disk and the pathfinder is rebuilt immediately, so no jar rebuild is needed.
   */
  private void handleConfig(Player player, String[] args) {
    PathfinderSettings settings = pathfinderManager.settings();

    // "/path config" -> list every option with its current value
    if (args.length == 1) {
      player.sendMessage("Pathfinder configuration (change with /path config <key> <value>):");
      for (PathfinderSettings.Option option : settings.options()) {
        player.sendMessage(
            " - "
                + option.key()
                + " = "
                + option.displayValue()
                + "  ("
                + option.description()
                + ")");
      }
      return;
    }

    // "/path config reload" -> re-read config.yml from disk and apply it
    if (args.length == 2 && args[1].equalsIgnoreCase("reload")) {
      plugin.reloadConfig();
      settings.load(plugin.getConfig());
      pathfinderManager.rebuild();
      player.sendMessage("Reloaded config.yml and rebuilt the pathfinder.");
      return;
    }

    // "/path config <key> <value>" -> set, save, and rebuild
    if (args.length < 3) {
      player.sendMessage("Usage: /path config <key> <value>  |  /path config reload");
      return;
    }

    try {
      PathfinderSettings.Option option = settings.set(args[1], args[2]);
      settings.save();
      pathfinderManager.rebuild();
      player.sendMessage(
          "Set " + option.key() + " = " + option.displayValue() + " and rebuilt the pathfinder.");
    } catch (IllegalArgumentException e) {
      player.sendMessage("Could not set value: " + e.getMessage());
    }
  }

  // Provide tab completion for the command
  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String label, String[] args) {

    if (args.length == 1) {
      return filter(Arrays.asList("pos1", "pos2", "start", "swarm", "config"), args[0]);
    }

    if (args[0].equalsIgnoreCase("config")) {
      // Second argument: "reload" or any option key
      if (args.length == 2) {
        List<String> keys = new ArrayList<>();
        keys.add("reload");
        for (PathfinderSettings.Option option : pathfinderManager.settings().options()) {
          keys.add(option.key());
        }
        return filter(keys, args[1]);
      }

      // Third argument: the value suggestions for the chosen key
      if (args.length == 3) {
        for (PathfinderSettings.Option option : pathfinderManager.settings().options()) {
          if (option.key().equalsIgnoreCase(args[1])) {
            return filter(option.suggestions(), args[2]);
          }
        }
      }
    }

    return Collections.emptyList();
  }

  // Keep only suggestions that start with what the player has typed so far
  private static List<String> filter(List<String> suggestions, String input) {
    String prefix = input.toLowerCase(Locale.ROOT);
    List<String> matches = new ArrayList<>();
    for (String suggestion : suggestions) {
      if (suggestion.toLowerCase(Locale.ROOT).startsWith(prefix)) {
        matches.add(suggestion);
      }
    }
    return matches;
  }

  /**
   * Continuous throughput benchmark: fires a fixed number of pathfinding requests per tick with
   * random targets around the player and shows live statistics on the action bar.
   */
  private static class SwarmSession {

    // 500 requests per tick at 20 TPS = 10,000 paths per second
    static final int PATHS_PER_SECOND = 10_000;
    private static final int PATHS_PER_TICK = PATHS_PER_SECOND / 20;

    // Random target distance around the player; averages ~12 blocks like the classic swarm demo
    private static final double MIN_TARGET_DISTANCE = 6;
    private static final double MAX_TARGET_DISTANCE = 18;

    private final JavaPlugin plugin;
    private final PathfinderManager pathfinderManager;
    private final Player player;
    private final Random random = new Random();

    // Written from pathfinding callbacks on executor threads, read on the main thread
    private final AtomicLong windowCompleted = new AtomicLong();
    private final AtomicLong windowLatencyNanos = new AtomicLong();

    private long totalCompleted;
    private long totalLatencyNanos;
    private long startedAtMillis;
    private int ticks;
    private BukkitTask task;

    private SwarmSession(JavaPlugin plugin, PathfinderManager pathfinderManager, Player player) {
      this.plugin = plugin;
      this.pathfinderManager = pathfinderManager;
      this.player = player;
    }

    void start() {
      startedAtMillis = System.currentTimeMillis();
      task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    private void tick() {
      if (!player.isOnline()) {
        SWARM_MAP.remove(player.getUniqueId());
        stop();
        return;
      }

      // Pick up the current pathfinder each tick, so a "/path config" change applies mid-swarm.
      Pathfinder pathfinder = pathfinderManager.pathfinder();
      PathPosition center = BukkitMapper.toPathPosition(player.getLocation());
      BukkitEnvironmentContext context = new BukkitEnvironmentContext(player.getWorld());

      for (int i = 0; i < PATHS_PER_TICK; i++) {
        // Random direction and distance, so the swarm spreads around the player
        double angle = random.nextDouble() * 2 * Math.PI;
        double distance =
            MIN_TARGET_DISTANCE + random.nextDouble() * (MAX_TARGET_DISTANCE - MIN_TARGET_DISTANCE);
        PathPosition target =
            center.add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);

        // Latency is measured from submission to completion, including executor queue time.
        // Every search ends in exactly one of ifPresent/orElse, so each one is counted once.
        final long submittedAt = System.nanoTime();
        Consumer<PathfinderResult> record =
            result -> {
              windowCompleted.incrementAndGet();
              windowLatencyNanos.addAndGet(System.nanoTime() - submittedAt);
            };
        pathfinder.findPath(center, target, context).ifPresent(record).orElse(record);
      }

      // Once per second: roll the window and update the action bar
      if (++ticks % 20 == 0) {
        long completed = windowCompleted.getAndSet(0);
        long latencyNanos = windowLatencyNanos.getAndSet(0);
        totalCompleted += completed;
        totalLatencyNanos += latencyNanos;

        double avgPerPathMicros = completed == 0 ? 0 : latencyNanos / 1_000.0 / completed;
        double totalMillis = latencyNanos / 1_000_000.0;

        player
            .spigot()
            .sendMessage(
                ChatMessageType.ACTION_BAR,
                new TextComponent(
                    String.format(
                        "Ø %.1f µs/path | Ø total %.1f ms/s | %d paths/s",
                        avgPerPathMicros, totalMillis, completed)));
      }
    }

    void stop() {
      task.cancel();

      long runtimeMillis = System.currentTimeMillis() - startedAtMillis;
      double overallAvgMicros =
          totalCompleted == 0 ? 0 : totalLatencyNanos / 1_000.0 / totalCompleted;
      double overallPathsPerSecond =
          runtimeMillis == 0 ? 0 : totalCompleted * 1000.0 / runtimeMillis;

      player.sendMessage("Swarm stopped after " + (runtimeMillis / 1000) + "s:");
      player.sendMessage(" - " + totalCompleted + " paths completed");
      player.sendMessage(String.format(" - Ø %.1f µs/path", overallAvgMicros));
      player.sendMessage(String.format(" - Ø %.0f paths/s", overallPathsPerSecond));
    }
  }

  // Class to manage player session data
  private static class PlayerSession {

    private Location pos1;
    private Location pos2;

    public void setPos1(Location pos1) {
      this.pos1 = pos1;
    }

    public void setPos2(Location pos2) {
      this.pos2 = pos2;
    }

    // Check if both positions are set
    public boolean isComplete() {
      return pos1 != null && pos2 != null;
    }

    public Location getPos1() {
      return pos1;
    }

    public Location getPos2() {
      return pos2;
    }
  }
}
