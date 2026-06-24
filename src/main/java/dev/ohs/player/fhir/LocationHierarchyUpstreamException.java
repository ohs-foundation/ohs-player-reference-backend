package dev.ohs.player.fhir;

/**
 * Thrown when the upstream FHIR store fails or returns an unusable response while building a
 * Location hierarchy. Maps to HTTP 502. The endpoint fails closed and never returns a partial tree
 * on upstream failure.
 *
 * <p>Contract: the service must wrap any FHIR transport/server/parse error during descendant
 * traversal — including an unexpected child-search {@code 404} — in this exception. Only the
 * initial root read may surface a {@link ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException}
 * (mapped to 404).
 */
public class LocationHierarchyUpstreamException extends RuntimeException {

  public LocationHierarchyUpstreamException(String message) {
    super(message);
  }

  public LocationHierarchyUpstreamException(String message, Throwable cause) {
    super(message, cause);
  }
}
