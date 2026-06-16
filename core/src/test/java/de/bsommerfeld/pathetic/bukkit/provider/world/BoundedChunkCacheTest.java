package de.bsommerfeld.pathetic.bukkit.provider.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoundedChunkCacheTest {

  private static final long ONE_MINUTE = TimeUnit.MINUTES.toMillis(1);

  @Test
  void neverExceedsCapacity() {
    BoundedChunkCache<String> cache = new BoundedChunkCache<>(4, ONE_MINUTE, 5, false, 15);
    for (long key = 0; key < 100; key++) {
      cache.put(key, "v" + key);
      assertTrue(cache.size() <= 4);
    }
    assertEquals(4, cache.size());
  }

  @Test
  void evictsTheLeastHotEntry() {
    BoundedChunkCache<String> cache = new BoundedChunkCache<>(4, ONE_MINUTE, 5, false, 15);
    cache.put(1, "a");
    cache.put(2, "b");
    cache.put(3, "c"); // the cold one
    cache.put(4, "d");

    // Warm up 1, 2 and 4; 3 stays at its initial heat.
    for (int i = 0; i < 5; i++) {
      cache.get(1);
      cache.get(2);
      cache.get(4);
    }

    cache.put(5, "e"); // over capacity -> evict the least-hot sampled entry

    assertNull(cache.get(3), "the coldest entry should be evicted");
    assertNotNull(cache.get(1));
    assertNotNull(cache.get(2));
    assertNotNull(cache.get(4));
    assertNotNull(cache.get(5));
  }

  @Test
  void coolsDownAndDropsWhenIdle() throws InterruptedException {
    BoundedChunkCache<String> cache = new BoundedChunkCache<>(8, 10, 5, false, 15); // 1 heat lost per 10ms
    cache.put(1, "a");
    assertNotNull(cache.get(1));

    Thread.sleep(60); // long enough to cool from full heat to zero

    assertNull(cache.get(1), "a chunk left idle long enough should cool to zero and drop");
  }

  @Test
  void sweepRemovesCooledChunks() throws InterruptedException {
    BoundedChunkCache<String> cache = new BoundedChunkCache<>(100, 10, 5, false, 15); // 1 heat lost per 10ms
    cache.put(1, "a");
    cache.put(2, "b");
    assertEquals(2, cache.size());

    Thread.sleep(60); // both cool to zero

    cache.sweepCooled();
    assertEquals(0, cache.size(), "the sweep should drop chunks that have cooled to zero");
  }

  @Test
  void sweepKeepsWarmChunks() throws InterruptedException {
    BoundedChunkCache<String> cache = new BoundedChunkCache<>(100, 50, 5, false, 15); // 1 heat lost per 50ms
    cache.put(1, "a");
    cache.get(1); // warm it

    Thread.sleep(20); // less than one decay interval -> still warm

    cache.sweepCooled();
    assertEquals(1, cache.size(), "the sweep must not touch a still-warm chunk");
  }

  @Test
  void memoryPressureEvictionKeepsTheCacheSmall() {
    // minFreeHeapPercent = 100 -> always "under pressure", so every insert sheds a cold batch.
    BoundedChunkCache<String> cache = new BoundedChunkCache<>(100_000, ONE_MINUTE, 5, true, 100);
    for (long key = 0; key < 1000; key++) {
      cache.put(key, "v" + key);
    }
    assertTrue(cache.size() <= 16, "memory pressure should keep the cache from growing toward its ceiling");
  }

  @Test
  void noMemoryPressureEvictionWhenDisabled() {
    // Same always-pressure threshold, but the feature is off -> the count ceiling alone bounds it.
    BoundedChunkCache<String> cache = new BoundedChunkCache<>(100_000, ONE_MINUTE, 5, false, 100);
    for (long key = 0; key < 1000; key++) {
      cache.put(key, "v" + key);
    }
    assertEquals(1000, cache.size(), "with the feature off, nothing is shed below the ceiling");
  }

  @Test
  void stayingActiveKeepsAHotChunkAlive() throws InterruptedException {
    BoundedChunkCache<String> cache = new BoundedChunkCache<>(8, 10, 5, false, 15); // 1 heat lost per 10ms
    cache.put(1, "a");

    // Keep touching it across a span longer than the decay window; each access resets it.
    for (int i = 0; i < 10; i++) {
      Thread.sleep(5);
      assertNotNull(cache.get(1), "an actively-used chunk must never expire");
    }
  }
}
