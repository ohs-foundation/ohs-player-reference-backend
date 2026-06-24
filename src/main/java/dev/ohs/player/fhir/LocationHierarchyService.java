package dev.ohs.player.fhir;

public class LocationHierarchyService {

  /**
   * Builds the Location hierarchy rooted at {@code rootId}.
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
   *   <li>Throws {@link LocationHierarchyCacheException} when Redis is unavailable or cache
   *       coordination cannot complete safely (503).
   * </ul>
   */
  public LocationHierarchy getLocationHierarchy(String rootId) {
    throw new UnsupportedOperationException("Location hierarchy service is not implemented");
  }
}
