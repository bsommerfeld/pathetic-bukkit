package de.bsommerfeld.pathetic.example.config;

import de.bsommerfeld.pathetic.api.pathing.INeighborStrategy;
import de.bsommerfeld.pathetic.api.pathing.NeighborStrategies;
import de.bsommerfeld.pathetic.api.pathing.configuration.PathfinderConfiguration;
import de.bsommerfeld.pathetic.api.pathing.heuristic.HeuristicStrategies;
import de.bsommerfeld.pathetic.api.pathing.heuristic.IHeuristicStrategy;
import de.bsommerfeld.pathetic.api.pathing.hook.PathfinderHook;
import de.bsommerfeld.pathetic.api.pathing.processing.ValidationProcessor;
import de.bsommerfeld.pathetic.bukkit.hook.MetricsHook;
import de.bsommerfeld.pathetic.bukkit.hook.PreloadingHook;
import de.bsommerfeld.pathetic.bukkit.hook.SpigotPathfindingHook;
import de.bsommerfeld.pathetic.bukkit.provider.LoadingNavigationPointProvider;
import de.bsommerfeld.pathetic.example.processor.SimpleValidationProcessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Holds every configurable {@link PathfinderConfiguration} value as a named, validated option.
 *
 * <p>The same options back both the {@code config.yml} file and the in-game {@code /path config}
 * command, so values can be loaded from disk, changed at runtime, saved back, and turned into a
 * fresh {@link PathfinderConfiguration} - all without rebuilding the jar.
 */
public final class PathfinderSettings {

  private final JavaPlugin plugin;

  // Insertion order is the order shown by "/path config" and written to config.yml.
  private final Map<String, Option> options = new LinkedHashMap<>();

  public PathfinderSettings(JavaPlugin plugin) {
    this.plugin = plugin;

    register("max-iterations", "Max A* iterations before giving up", 1_000_000,
        intParser("max-iterations", 1), Collections.emptyList());
    register("max-length", "Max path length, 0 = unlimited", 0,
        intParser("max-length", 0), Collections.emptyList());
    register("async", "Run searches off the main thread", true,
        PathfinderSettings::parseBool, boolSuggestions());
    register("fallback", "Return the best partial path on failure", true,
        PathfinderSettings::parseBool, boolSuggestions());
    register("reopen-closed-nodes", "Re-evaluate closed nodes (slower, more accurate)", false,
        PathfinderSettings::parseBool, boolSuggestions());
    register("heuristic", "SQUARED (fast) or LINEAR (accurate)", "SQUARED",
        choiceParser("heuristic", "LINEAR", "SQUARED"), Arrays.asList("LINEAR", "SQUARED"));
    register("neighbor-strategy", "VERTICAL_AND_HORIZONTAL (6) or DIAGONAL_3D (26)",
        "VERTICAL_AND_HORIZONTAL",
        choiceParser("neighbor-strategy", "VERTICAL_AND_HORIZONTAL", "DIAGONAL_3D"),
        Arrays.asList("VERTICAL_AND_HORIZONTAL", "DIAGONAL_3D"));
    register("hook-spigot", "Register SpigotPathfindingHook", true,
        PathfinderSettings::parseBool, boolSuggestions());
    register("hook-metrics", "Register the bStats MetricsHook", true,
        PathfinderSettings::parseBool, boolSuggestions());
    register("hook-preloading", "Register the chunk-corridor PreloadingHook", true,
        PathfinderSettings::parseBool, boolSuggestions());
    register("validation", "Register the example SimpleValidationProcessor", true,
        PathfinderSettings::parseBool, boolSuggestions());
  }

  private void register(
      String key,
      String description,
      Object def,
      Function<String, Object> parser,
      List<String> suggestions) {
    options.put(key, new Option(key, description, def, parser, suggestions));
  }

  /** All options in display order. */
  public Collection<Option> options() {
    return Collections.unmodifiableCollection(options.values());
  }

  /**
   * Parses and applies {@code value} to the option named {@code key}.
   *
   * @return the updated option
   * @throws IllegalArgumentException if the key is unknown or the value is invalid
   */
  public Option set(String key, String value) {
    Option option = options.get(key.toLowerCase(Locale.ROOT));
    if (option == null) {
      throw new IllegalArgumentException(
          "Unknown option '" + key + "'. Run /path config to list options.");
    }
    option.set(value);
    return option;
  }

  /** Reads any values present in {@code cfg}, keeping the current value when one is missing/invalid. */
  public void load(FileConfiguration cfg) {
    for (Option option : options.values()) {
      if (!cfg.isSet(option.key())) continue;
      try {
        option.set(String.valueOf(cfg.get(option.key())));
      } catch (IllegalArgumentException e) {
        plugin
            .getLogger()
            .warning(
                "Invalid config value for '"
                    + option.key()
                    + "': "
                    + e.getMessage()
                    + " - keeping "
                    + option.displayValue());
      }
    }
  }

  /** Writes every option back to the plugin config and saves it to disk. */
  public void save() {
    FileConfiguration cfg = plugin.getConfig();
    for (Option option : options.values()) {
      cfg.set(option.key(), option.value);
    }
    plugin.saveConfig();
  }

  /** Builds a fresh {@link PathfinderConfiguration} from the current option values. */
  public PathfinderConfiguration toConfiguration() {
    IHeuristicStrategy heuristic =
        "LINEAR".equals(stringValue("heuristic"))
            ? HeuristicStrategies.LINEAR
            : HeuristicStrategies.SQUARED;
    INeighborStrategy neighborStrategy =
        "DIAGONAL_3D".equals(stringValue("neighbor-strategy"))
            ? NeighborStrategies.DIAGONAL_3D
            : NeighborStrategies.VERTICAL_AND_HORIZONTAL;

    List<PathfinderHook> hooks = new ArrayList<>();
    if (boolValue("hook-spigot")) hooks.add(new SpigotPathfindingHook());
    if (boolValue("hook-metrics")) hooks.add(new MetricsHook());
    if (boolValue("hook-preloading")) hooks.add(new PreloadingHook());

    List<ValidationProcessor> validators =
        boolValue("validation")
            ? Collections.singletonList(new SimpleValidationProcessor())
            : Collections.emptyList();

    return PathfinderConfiguration.builder()
        .provider(new LoadingNavigationPointProvider())
        .maxIterations(intValue("max-iterations"))
        .maxLength(intValue("max-length"))
        .async(boolValue("async"))
        .fallback(boolValue("fallback"))
        .reopenClosedNodes(boolValue("reopen-closed-nodes"))
        .heuristicStrategy(heuristic)
        .neighborStrategy(neighborStrategy)
        .validationProcessors(validators)
        .pathfindingHooks(hooks)
        .build();
  }

  private int intValue(String key) {
    return (Integer) options.get(key).value;
  }

  private boolean boolValue(String key) {
    return (Boolean) options.get(key).value;
  }

  private String stringValue(String key) {
    return (String) options.get(key).value;
  }

  // ---- Parsers -------------------------------------------------------------

  private static Function<String, Object> intParser(String name, int min) {
    return raw -> {
      int value;
      try {
        value = Integer.parseInt(raw.trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(name + " must be a whole number, was '" + raw + "'");
      }
      if (value < min) {
        throw new IllegalArgumentException(name + " must be >= " + min + ", was " + value);
      }
      return value;
    };
  }

  private static Object parseBool(String raw) {
    String token = raw.trim().toLowerCase(Locale.ROOT);
    if (token.equals("true")) return Boolean.TRUE;
    if (token.equals("false")) return Boolean.FALSE;
    throw new IllegalArgumentException("expected true or false, was '" + raw + "'");
  }

  private static Function<String, Object> choiceParser(String name, String... allowed) {
    List<String> choices = Arrays.asList(allowed);
    return raw -> {
      String token = raw.trim().toUpperCase(Locale.ROOT);
      if (!choices.contains(token)) {
        throw new IllegalArgumentException(name + " must be one of " + choices + ", was '" + raw + "'");
      }
      return token;
    };
  }

  private static List<String> boolSuggestions() {
    return Arrays.asList("true", "false");
  }

  /** A single named, validated configuration value. */
  public static final class Option {

    private final String key;
    private final String description;
    private final List<String> suggestions;
    private final Function<String, Object> parser;
    private Object value;

    private Option(
        String key,
        String description,
        Object def,
        Function<String, Object> parser,
        List<String> suggestions) {
      this.key = key;
      this.description = description;
      this.parser = parser;
      this.suggestions = suggestions;
      this.value = def;
    }

    public String key() {
      return key;
    }

    public String description() {
      return description;
    }

    public List<String> suggestions() {
      return suggestions;
    }

    public String displayValue() {
      return String.valueOf(value);
    }

    private void set(String raw) {
      this.value = parser.apply(raw);
    }
  }
}
