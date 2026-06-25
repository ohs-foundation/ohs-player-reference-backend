package dev.ohs.player.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.api.SearchStyleEnum;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Reference;

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
    Location rootLocation = readRoot(client, rootId);
    LocationNode rootNode = mapLocation(rootLocation);
    rootNode.setPartOf(null);

    LocationHierarchyMeta meta = new LocationHierarchyMeta();
    meta.setNodeCount(1);
    meta.setDepth(0);
    meta.setTruncated(false);
    meta.setBuiltAt(Instant.now());

    LocationHierarchy hierarchy = new LocationHierarchy();
    hierarchy.setRoot(rootNode);
    hierarchy.setMeta(meta);
    return hierarchy;
  }

  private Location readRoot(IGenericClient client, String rootId) {
    try {
      return client.read().resource(Location.class).withId(rootId).execute();
    } catch (ResourceNotFoundException e) {
      throw e;
    } catch (FhirClientConnectionException | DataFormatException e) {
      throw new LocationHierarchyUpstreamException("Failed to read root Location", e);
    } catch (BaseServerResponseException e) {
      throw new LocationHierarchyUpstreamException(
          "FHIR server failed while reading root Location", e);
    }
  }

  Map<String, List<LocationNode>> fetchChildrenForBatch(
      IGenericClient client, List<LocationNode> parents, Set<String> emittedIds) {
    if (parents == null || parents.isEmpty()) {
      return Collections.emptyMap();
    }

    List<String> parentIds = parentLogicalIds(parents);
    Map<String, List<LocationNode>> childrenByParent = new HashMap<>();
    Set<String> visitedNextUrls = new HashSet<>();

    for (Bundle page = executeChildSearch(client, parentIds); page != null; ) {
      validateSearchBundle(page);
      processChildSearchPage(page, childrenByParent, emittedIds);

      Bundle.BundleLinkComponent nextLink = page.getLink("next");
      if (nextLink == null) {
        break;
      }

      String nextUrl = nextLink.getUrl();
      if (nextUrl == null || nextUrl.isBlank()) {
        throw new LocationHierarchyUpstreamException(
            "FHIR child search returned a blank next page link");
      }
      if (!visitedNextUrls.add(nextUrl)) {
        throw new LocationHierarchyUpstreamException(
            "FHIR child search returned a repeated next page link");
      }

      page = fetchNextPage(client, page);
    }
    return childrenByParent;
  }

  private Bundle executeChildSearch(IGenericClient client, List<String> parentIds) {
    try {
      return client
          .search()
          .forResource(Location.class)
          .where(Location.PARTOF.hasAnyOfIds(parentIds))
          .count(config.getUpstreamPageSize())
          .usingStyle(SearchStyleEnum.POST)
          .returnBundle(Bundle.class)
          .execute();
    } catch (ResourceNotFoundException e) {
      throw new LocationHierarchyUpstreamException(
          "FHIR child search unexpectedly returned not found", e);
    } catch (FhirClientConnectionException | DataFormatException e) {
      throw new LocationHierarchyUpstreamException("Failed to search child Locations", e);
    } catch (BaseServerResponseException e) {
      throw new LocationHierarchyUpstreamException(
          "FHIR server failed while searching child Locations", e);
    }
  }

  private Bundle fetchNextPage(IGenericClient client, Bundle currentPage) {
    try {
      return client.loadPage().next(currentPage).execute();
    } catch (ResourceNotFoundException e) {
      throw new LocationHierarchyUpstreamException(
          "FHIR child search next page unexpectedly returned not found", e);
    } catch (FhirClientConnectionException | DataFormatException e) {
      throw new LocationHierarchyUpstreamException("Failed to fetch child Location page", e);
    } catch (BaseServerResponseException e) {
      throw new LocationHierarchyUpstreamException(
          "FHIR server failed while fetching child Location page", e);
    }
  }

  private void validateSearchBundle(Bundle bundle) {
    if (bundle == null) {
      throw new LocationHierarchyUpstreamException("FHIR child search returned no Bundle");
    }
    if (bundle.getType() != Bundle.BundleType.SEARCHSET) {
      throw new LocationHierarchyUpstreamException(
          "FHIR child search returned a non-searchset Bundle");
    }
  }

  private void processChildSearchPage(
      Bundle page, Map<String, List<LocationNode>> childrenByParent, Set<String> emittedIds) {
    // Task 5 owns search-entry validation and hierarchy edge processing. Task 4 only drains and
    // validates the search pages.
  }

  private List<String> parentLogicalIds(List<LocationNode> parents) {
    List<String> parentIds = new ArrayList<>(parents.size());
    for (LocationNode parent : parents) {
      String parentId = normalizeLocationReference(parent.getId());
      parentIds.add(parentId);
    }
    Collections.sort(parentIds);
    return parentIds;
  }

  LocationNode mapLocation(Location location) {
    String logicalId = normalizeLocationId(location);

    LocationNode node = new LocationNode();
    node.setId(canonicalLocationReference(logicalId));
    node.setName(location.hasName() ? location.getName() : null);
    node.setPartOf(normalizePartOf(location.getPartOf()));
    return node;
  }

  private String normalizeLocationId(Location location) {
    String logicalId = location.getIdElement().toUnqualifiedVersionless().getIdPart();
    if (logicalId == null || logicalId.isBlank()) {
      throw new LocationHierarchyUpstreamException("Root Location is missing a logical id");
    }
    return logicalId;
  }

  private String normalizePartOf(Reference partOf) {
    if (partOf == null || partOf.isEmpty()) {
      return null;
    }

    String parentId = partOf.getReferenceElement().toUnqualifiedVersionless().getIdPart();
    if (parentId == null || parentId.isBlank()) {
      throw new LocationHierarchyUpstreamException("Location.partOf is missing a logical id");
    }
    return canonicalLocationReference(parentId);
  }

  private String normalizeLocationReference(String reference) {
    String logicalId =
        new org.hl7.fhir.r4.model.IdType(reference).toUnqualifiedVersionless().getIdPart();
    if (logicalId == null || logicalId.isBlank()) {
      throw new LocationHierarchyUpstreamException("Location reference is missing a logical id");
    }
    return logicalId;
  }

  private String canonicalLocationReference(String logicalId) {
    return "Location/" + logicalId;
  }
}
