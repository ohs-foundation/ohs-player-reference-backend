package dev.ohs.player.configs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.benmanes.caffeine.cache.Cache;
import dev.ohs.player.fhir.LocationHierarchy;
import dev.ohs.player.fhir.LocationHierarchyMeta;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OtherConfigsLocationHierarchyCacheTest {

  private final OtherConfigs configs = new OtherConfigs();

  @Test
  void locationHierarchyCache_ConfiguresExpiryAndNodeWeight() {
    Cache<String, LocationHierarchy> cache = configs.locationHierarchyCache(60, 30);

    assertEquals(
        Duration.ofSeconds(60), cache.policy().expireAfterWrite().orElseThrow().getExpiresAfter());
    assertTrue(cache.policy().eviction().orElseThrow().isWeighted());
    assertEquals(30, cache.policy().eviction().orElseThrow().getMaximum());

    cache.put("root-a", hierarchyWithNodeCount(10));
    cache.put("root-b", hierarchyWithNodeCount(10));
    cache.put("root-c", hierarchyWithNodeCount(15));
    cache.put("root-d", hierarchyWithNodeCount(20));

    cache.cleanUp();

    assertTrue(cache.estimatedSize() > 0);
    assertTrue(cache.estimatedSize() < 4);
    assertTrue(cache.policy().eviction().orElseThrow().weightedSize().orElseThrow() <= 30);
  }

  @Test
  void locationHierarchyCache_RejectsNonPositiveTtl() {
    assertThrows(IllegalArgumentException.class, () -> configs.locationHierarchyCache(0, 100));
  }

  @Test
  void locationHierarchyCache_RejectsNonPositiveMaximumWeight() {
    assertThrows(IllegalArgumentException.class, () -> configs.locationHierarchyCache(60, 0));
  }

  private LocationHierarchy hierarchyWithNodeCount(int nodeCount) {
    LocationHierarchyMeta meta = new LocationHierarchyMeta();
    meta.setNodeCount(nodeCount);
    LocationHierarchy hierarchy = new LocationHierarchy();
    hierarchy.setMeta(meta);
    return hierarchy;
  }
}
