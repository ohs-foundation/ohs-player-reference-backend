package dev.ohs.player.fhir;

/** Immutable traversal configuration for {@link LocationHierarchyService}. */
public final class LocationHierarchyConfig {

  private final int maxPartOfBatchSize;
  private final int upstreamPageSize;
  private final int maxDepth;
  private final int maxNodes;

  public LocationHierarchyConfig(
      int maxPartOfBatchSize, int upstreamPageSize, int maxDepth, int maxNodes) {
    if (maxPartOfBatchSize <= 0) {
      throw new IllegalArgumentException("maxPartOfBatchSize must be greater than zero");
    }
    if (upstreamPageSize <= 0) {
      throw new IllegalArgumentException("upstreamPageSize must be greater than zero");
    }
    if (maxDepth < 0) {
      throw new IllegalArgumentException("maxDepth must be zero or greater");
    }
    if (maxNodes <= 0) {
      throw new IllegalArgumentException("maxNodes must be greater than zero");
    }

    this.maxPartOfBatchSize = maxPartOfBatchSize;
    this.upstreamPageSize = upstreamPageSize;
    this.maxDepth = maxDepth;
    this.maxNodes = maxNodes;
  }

  public int getMaxPartOfBatchSize() {
    return maxPartOfBatchSize;
  }

  public int getUpstreamPageSize() {
    return upstreamPageSize;
  }

  public int getMaxDepth() {
    return maxDepth;
  }

  public int getMaxNodes() {
    return maxNodes;
  }
}
