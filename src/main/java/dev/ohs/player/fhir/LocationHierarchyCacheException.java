package dev.ohs.player.fhir;

/**
 * Thrown when the shared Redis cache is unavailable or cache coordination cannot complete safely
 * while building a Location hierarchy. Maps to HTTP 503. The endpoint fails the request rather than
 * bypassing the cache and stampeding the FHIR store.
 */
public class LocationHierarchyCacheException extends RuntimeException {

  public LocationHierarchyCacheException(String message) {
    super(message);
  }

  public LocationHierarchyCacheException(String message, Throwable cause) {
    super(message, cause);
  }
}
