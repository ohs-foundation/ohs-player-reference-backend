package dev.ohs.player.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import com.github.benmanes.caffeine.cache.Cache;
import java.util.Objects;

public class LocationHierarchyService {

  private final Cache<String, LocationHierarchy> cache;
  private final FhirContext fhirContext;
  private final String fhirServerUrl;
  private final LocationHierarchyConfig config;

  public LocationHierarchyService(
      Cache<String, LocationHierarchy> cache,
      FhirContext fhirContext,
      String fhirServerUrl,
      LocationHierarchyConfig config) {
    this.cache = Objects.requireNonNull(cache, "cache cannot be null");
    this.fhirContext = Objects.requireNonNull(fhirContext, "fhirContext cannot be null");
    if (fhirServerUrl == null || fhirServerUrl.isBlank()) {
      throw new IllegalArgumentException("fhirServerUrl cannot be blank");
    }
    this.fhirServerUrl = fhirServerUrl;
    this.config = Objects.requireNonNull(config, "config cannot be null");
  }

  /**
   * Returns the cached Location hierarchy rooted at {@code rootId}, building it on a cache miss.
   * The returned hierarchy may be shared across request threads and must be treated as read-only.
   *
   * <p>The cache key is only {@code rootId} because traversal limits are process-wide constants in
   * v1. If traversal limits become request parameters, they must become part of the cache key.
   *
   * <p>The cache key also assumes response content is identical for every authorized caller. In v1,
   * authorization is enforced before this service is called and upstream reads use the configured
   * service account. If Location visibility ever becomes caller-dependent, the caller's effective
   * authorization scope must become part of the cache key.
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
    return cache.get(rootId, this::loadOnCacheMiss);
  }

  private LocationHierarchy loadOnCacheMiss(String rootId) {
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

  private IGenericClient newClient() {
    return fhirContext.newRestfulGenericClient(fhirServerUrl);
  }

  LocationHierarchy buildHierarchy(String rootId) {
    IGenericClient client = newClient();
    throw new UnsupportedOperationException("Location hierarchy service is not implemented");
  }
}
