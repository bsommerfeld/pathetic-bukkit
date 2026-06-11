package de.bsommerfeld.pathetic.example.command;

import de.bsommerfeld.pathetic.api.pathing.Pathfinder;
import de.bsommerfeld.pathetic.api.pathing.PathfindingSearch;
import de.bsommerfeld.pathetic.api.pathing.result.PathfinderResult;
import de.bsommerfeld.pathetic.api.wrapper.PathPosition;
import de.bsommerfeld.pathetic.bukkit.context.BukkitEnvironmentContext;
import de.bsommerfeld.pathetic.bukkit.mapper.BukkitMapper;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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

  // Pathfinder instance to handle pathfinding logic
  private final Pathfinder pathfinder;

  // Constructor to initialize the pathfinder
  public PatheticCommand(JavaPlugin plugin, Pathfinder pathfinder) {
    this.plugin = plugin;
    this.pathfinder = pathfinder;
  }

  // Handle command execution
  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

    // Ensure the sender is a player
    if (!(sender instanceof Player)) return false;

    // Ensure the command has exactly one argument
    if (args.length != 1) return false;

    // Cast sender to Player
    Player player = (Player) sender;

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
         * and where all the configuration stuff we initialized before does their job.
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

        SwarmSession swarmSession = new SwarmSession(plugin, pathfinder, player);
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

  // Provide tab completion for the command
  @Override
  public List<String> onTabComplete(
      CommandSender sender, Command command, String label, String[] args) {
    return Arrays.asList("pos1", "pos2", "start", "swarm");
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
    private final Pathfinder pathfinder;
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

    private SwarmSession(JavaPlugin plugin, Pathfinder pathfinder, Player player) {
      this.plugin = plugin;
      this.pathfinder = pathfinder;
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
