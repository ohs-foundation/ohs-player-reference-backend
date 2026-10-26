package dev.ohs.player.configs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.benmanes.caffeine.cache.Cache;
import dev.ohs.player.fhir.OrgScope;
import dev.ohs.player.fhir.PartOfOrganizationScopeExpander;
import org.junit.jupiter.api.Test;

class OtherConfigsOrgScopeTest {

  private final OtherConfigs configs = new OtherConfigs();

  @Test
  void orgScopeCache_UsesFixedTtlAndSize() {
    Cache<String, OrgScope> cache = configs.orgScopeCache();

    assertEquals(
        OtherConfigs.ORG_SCOPE_CACHE_TTL,
        cache.policy().expireAfterWrite().orElseThrow().getExpiresAfter());
    assertEquals(
        OtherConfigs.ORG_SCOPE_CACHE_MAX_ENTRIES,
        cache.policy().eviction().orElseThrow().getMaximum());
  }

  @Test
  void organizationScopeExpander_DefaultLimits_BuildsPartOfExpander() {
    assertInstanceOf(
        PartOfOrganizationScopeExpander.class, configs.organizationScopeExpander(10, 2000));
  }

  @Test
  void organizationScopeExpander_InvalidLimit_FailsAtStartup() {
    assertThrows(IllegalArgumentException.class, () -> configs.organizationScopeExpander(0, 2000));
    assertThrows(IllegalArgumentException.class, () -> configs.organizationScopeExpander(10, 0));
  }
}
