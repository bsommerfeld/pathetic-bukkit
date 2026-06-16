# Pathetic Bukkit ![Downloads](https://jitpack.io/v/bsommerfeld/pathetic-bukkit/month.svg)

Pathetic is a high-performance A* pathfinding library for Minecraft servers, specifically designed for Bukkit, Spigot,
and Paper. It provides a robust and flexible API for computing efficient paths within the Minecraft world.

## Features

- High-performance A* pathfinding implementation with asynchronous computation
- Configurable pathfinding parameters and customizable cost calculation
- Native chunk loading support via NavigationPointProvider
- Disk-accelerated chunk retrieval: generated-but-unloaded terrain is read straight from the world's Anvil region files off the main thread, so long-distance searches no longer stall loading chunks one border at a time
- Corridor preloading at search start via `PreloadingHook`
- Extensive validation and cost processing system
- Built-in fallback strategies for complex pathfinding scenarios
- Minimal performance impact with configurable iteration limits

## Supported Platforms

Paper and Spigot are explicitly supported. Other server implementations may work but are not officially tested.

*This repository was previously part of the [Pathetic main repository](https://github.com/bsommerfeld/pathetic).*

## Installation

### Maven

```xml

<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
<dependency>
    <groupId>com.github.bsommerfeld.pathetic-bukkit</groupId>
    <artifactId>core</artifactId>
    <version>VERSION</version>
</dependency>
</dependencies>
```

### Gradle

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.bsommerfeld.pathetic-bukkit:core:VERSION'
}
```

**Relocation is strongly recommended** to avoid conflicts with other plugins using Pathetic.

<details>
<summary>Maven Shade Relocation</summary>

```xml

<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <version>3.5.0</version>
    <configuration>
        <relocations>
            <relocation>
                <pattern>de.bsommerfeld.pathetic</pattern>
                <shadedPattern>your.package.pathetic</shadedPattern>
            </relocation>
        </relocations>
    </configuration>
</plugin>
```

</details>

<details>
<summary>Gradle Shadow Relocation</summary>

```groovy
shadowJar {
    relocate 'de.bsommerfeld.pathetic', 'your.package.pathetic'
}
```

</details>

## Quick Start

### 1. Initialize Pathetic

```java
import de.bsommerfeld.pathetic.bukkit.PatheticBukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class MyPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        // Initialize Pathetic with this plugin instance
        PatheticBukkit.initialize(this);
    }

    @Override
    public void onDisable() {
        // REQUIRED: release Pathetic's shared resources
        PatheticBukkit.shutdown();
    }
}
```

> ⚠️ **You must call `PatheticBukkit.shutdown()` in `onDisable`.** Pathetic holds JVM-lifetime shared
> state — background threads (chunk prefetch + cache sweeper) and a static chunk cache — that is *not*
> tied to any individual `Pathfinder`. Skipping `shutdown()` leaks those threads and cached chunks
> across every plugin reload.

### Tuning the chunk cache (optional)

Pathetic caches decoded chunks per world, retaining them by **heat**: each search that uses a chunk
adds heat, idle time removes it, and a background sweep drops cooled chunks so the cache self-sizes
to what you actually path through. Defaults are sensible; override any of them via
`initialize(plugin, ChunkCacheConfiguration)`:

```java
import de.bsommerfeld.pathetic.bukkit.ChunkCacheConfiguration;
import java.util.concurrent.TimeUnit;

PatheticBukkit.initialize(this, ChunkCacheConfiguration.builder()
        .heatDecayInterval(20, TimeUnit.SECONDS) // 1 heat lost per 20s idle (so 20s/40s/60s... of life)
        .maxHeat(5)                              // max heat = max decay intervals a chunk survives idle
        .sweepInterval(30, TimeUnit.SECONDS)     // how often cooled chunks are pruned
        .maxCachedChunks(16384)                  // hard ceiling per world; omit for a heap-scaled default
        .memoryPressureEviction(true)            // also shed cold chunks early when free heap is low
        .minFreeHeapPercent(15)                  // ...below 15% free heap
        .build());
```

The size limit is a heap-scaled out-of-memory backstop, not a working size — the cache normally sits
far below it and shrinks itself when idle. With `memoryPressureEviction` enabled it additionally sheds
cold chunks whenever free heap drops below the threshold, so the cache uses only the memory actually
available and can't push the server toward OOM under sustained heavy pathfinding.

To control the prefetch threads yourself, supply an executor with `.prefetchExecutor(myPool)` — use a
pool **separate** from your search executor (prefetch does blocking disk IO, so sharing the search
pool would starve searches). Pathetic never shuts a supplied executor down.

### 2. Create a Pathfinder

```java
import de.bsommerfeld.pathetic.api.factory.PathfinderFactory;
import de.bsommerfeld.pathetic.api.factory.PathfinderInitializer;
import de.bsommerfeld.pathetic.api.pathing.Pathfinder;
import de.bsommerfeld.pathetic.api.pathing.configuration.PathfinderConfiguration;
import de.bsommerfeld.pathetic.bukkit.initializer.BukkitPathfinderInitializer;
import de.bsommerfeld.pathetic.bukkit.provider.LoadingNavigationPointProvider;
import de.bsommerfeld.pathetic.engine.factory.AStarPathfinderFactory;

// Create the PathfinderFactory
PathfinderFactory factory = new AStarPathfinderFactory();

        // Configure the pathfinder
        PathfinderConfiguration configuration = PathfinderConfiguration.builder()
                .provider(new LoadingNavigationPointProvider())
                .async(true)
                .maxIterations(100_000_000)
                .build();

        // Create the pathfinder instance
        Pathfinder pathfinder = factory.createPathfinder(configuration);
```

### 3. Calculate a Path

```java
import de.bsommerfeld.pathetic.api.wrapper.PathPosition;
import de.bsommerfeld.pathetic.bukkit.context.BukkitEnvironmentContext;
import de.bsommerfeld.pathetic.bukkit.mapper.BukkitMapper;
import org.bukkit.Location;

class PathfindingExample {

    private final Pathfinder pathfinder;

    public PathfindingExample(Pathfinder pathfinder) {
        this.pathfinder = pathfinder;
    }

    private void findPath(Location start, Location target) {

        PathPosition startPos = BukkitMapper.toPathPosition(start);
        PathPosition targetPos = BukkitMapper.toPathPosition(target);

        pathfinder.findPath(startPos, targetPos, new BukkitEnvironmentContext(world))
                .ifPresent(result -> {

                    // We have an usable result since it either found the path, or fallen back.
                    result.getPath.forEach(position -> {
                        Location location = BukkitMapper.toLocation(position, world);
                        // Do something with it.
                    });

                }).orElse(_ -> {
                    // Handle no path found scenario
                    System.out.println("No path found between start and target positions.");

                }).exceptionally(ex -> System.err.println("An exception occurred -> " + ex));
    }
}
```

## Advanced Usage

### Custom Cost Processors

Implement the `CostProcessor` interface to define custom movement costs:

```java
import de.bsommerfeld.pathetic.api.pathing.processing.Cost;
import de.bsommerfeld.pathetic.api.pathing.processing.CostProcessor;
import de.bsommerfeld.pathetic.api.pathing.processing.context.EvaluationContext;
import de.bsommerfeld.pathetic.api.provider.NavigationPointProvider;
import de.bsommerfeld.pathetic.bukkit.provider.BukkitNavigationPoint;
import org.bukkit.Material;

public class CustomCostProcessor implements CostProcessor {
    @Override
    public Cost calculateCostContribution(EvaluationContext context) {
        NavigationPointProvider provider = context.getNavigationPointProvider();

        BukkitNavigationPoint beneath = (BukkitNavigationPoint)
                provider.getNavigationPoint(
                        context.getCurrentPathPosition().subtract(0, 1, 0),
                        context.getEnvironmentContext()
                );

        if (beneath.getMaterial() == Material.STONE) {
            return Cost.of(20);
        }

        return Cost.ZERO;
    }
}
```

Add the processor to your configuration:

```java
PathfinderConfiguration configuration = PathfinderConfiguration.builder()
        .provider(new LoadingNavigationPointProvider())
        .costProcessors(List.of(new CustomCostProcessor()))
        .build();
```

### Custom Validation Processors

Control which nodes are valid for pathfinding:

```java
import de.bsommerfeld.pathetic.api.pathing.processing.ValidationProcessor;
import de.bsommerfeld.pathetic.api.pathing.processing.context.EvaluationContext;

public class CustomValidationProcessor implements ValidationProcessor {
    @Override
    public boolean validate(EvaluationContext context) {
        // Return true if the node is valid, false otherwise
        return true;
    }
}
```

Add to configuration:

```java
PathfinderConfiguration configuration = PathfinderConfiguration.builder()
        .provider(new LoadingNavigationPointProvider())
        .nodeValidationProcessors(List.of(new CustomValidationProcessor()))
        .build();
```

### Configuration Options

```java
PathfinderConfiguration configuration = PathfinderConfiguration.builder()
        .provider(new LoadingNavigationPointProvider())
        .async(true)                                    // Enable async pathfinding
        .fallback(true)                                 // Enable fallback strategies
        .maxIterations(100_000_000)                     // Maximum nodes to evaluate
        .heuristicStrategy(HeuristicStrategies.SQUARED) // Heuristic calculation
        .costProcessors(List.of(...))                   // Custom cost processors
        .nodeValidationProcessors(List.of(...))         // Custom validation processors
        .pathfindingHooks(List.of(new MetricsHook()))   // Opt-in hook to support development
        .build();
```

## Navigation Point Providers

Pathetic provides two built-in providers:

- `LoadingNavigationPointProvider`: Loads chunks automatically if needed (recommended). Chunks that are generated but not loaded are read directly from the world's region files on disk, off the main thread, instead of being loaded through the server.
- `FailingNavigationPointProvider`: Fails if chunks are not loaded

```java
// With automatic chunk loading
.provider(new LoadingNavigationPointProvider())

// Without automatic chunk loading
// .provider(new FailingNavigationPointProvider())
```

## Performance Considerations

- Always use asynchronous pathfinding to avoid blocking the main thread
- Register the `PreloadingHook` (via `pathfindingHooks`) for long-distance searches so the chunk corridor is warmed in parallel ahead of the search front
- Set appropriate `maxIterations` limits based on your use case
- Use validation processors to eliminate invalid nodes early
- Consider using fallback strategies for complex scenarios
- Relocate the library to prevent conflicts with other plugins

## Examples

See the [example module](https://github.com/bsommerfeld/pathetic-bukkit/tree/trunk/example) for complete working
implementations, including:

- Basic pathfinding setup
- Custom cost and validation processors
- Integration with Bukkit commands
- Chunk invalidation handling

## Documentation

\+ See the [Pathetic Wiki](https://github.com/bsommerfeld/pathetic/wiki).

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Support

For issues and feature requests, please use the [GitHub Issues](https://github.com/bsommerfeld/pathetic-bukkit/issues)
page.
