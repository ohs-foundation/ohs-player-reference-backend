package dev.ohs.player.fhir;

import com.github.benmanes.caffeine.cache.Cache;
import java.util.Objects;

public class LocationHierarchyService {

  private final Cache<String, LocationHierarchy> cache;

  public LocationHierarchyService(Cache<String, LocationHierarchy> cache) {
    this.cache = Objects.requireNonNull(cache, "cache cannot be null");
  }

  /**
   * Returns the cached Location hierarchy rooted at {@code rootId}, building it on a cache miss.
   * The returned hierarchy may be shared across request threads and must be treated as read-only.
   *
   * <p>The cache key is only {@code rootId} because traversal limits are process-wide constants in
   * v1. If traversal limits become request parameters, they must become part of the cache key.
   *
   * <p>Error contract (mapped to HTTP status by {@code LocationHierarchyServlet}):
   *
   * <ul>
   *   <li>Returns a non-null {@link LocationHierarchy} on success. Must never return {@code null}.
   *   <li>Throws {@link ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException} only when the
   *       initial root read misses (404).
   *   <li>Throws {@link LocationHierarchyUpstreamException} for any FHIR transport/server/parse
   *       failure during descendant traversal, including an unexpected child-search 404 (502,
   *       fail-closed — never a partial tree).
   * </ul>
   */
  public LocationHierarchy getLocationHierarchy(String rootId) {
    Objects.requireNonNull(rootId, "rootId cannot be null");
    return cache.get(rootId, this::buildAndValidateHierarchy);
  }

  private LocationHierarchy buildAndValidateHierarchy(String rootId) {
    LocationHierarchy hierarchy = buildHierarchy(rootId);
    if (hierarchy == null) {
      throw new IllegalStateException("Built Location hierarchy cannot be null");
    }
    if (hierarchy.getRoot() == null) {
      throw new IllegalStateException("Built Location hierarchy must include a root");
    }
    if (hierarchy.getMeta() == null) {
      throw new IllegalStateException("Built Location hierarchy must include metadata");
    }
    if (hierarchy.getMeta().getNodeCount() <= 0) {
      throw new IllegalStateException(
          "Built Location hierarchy node count must be greater than zero");
    }
    if (hierarchy.getMeta().getBuiltAt() == null) {
      throw new IllegalStateException("Built Location hierarchy metadata must include builtAt");
    }
    return hierarchy;
  }

  LocationHierarchy buildHierarchy(String rootId) {
    throw new UnsupportedOperationException("Location hierarchy service is not implemented");
  }
}
