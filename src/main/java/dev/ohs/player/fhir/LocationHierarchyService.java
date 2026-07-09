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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocationHierarchyService {

  private static final Logger logger = LoggerFactory.getLogger(LocationHierarchyService.class);

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
    validateCacheAdmission(hierarchy);
    return hierarchy;
  }

  private void validateCacheAdmission(LocationHierarchy hierarchy) {
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
  }

  private IGenericClient newClient() {
    return fhirContext.newRestfulGenericClient(fhirServerUrl);
  }

  LocationHierarchy buildHierarchy(String rootId) {
    IGenericClient client = newClient();
    Location rootLocation = readRootLocation(client, rootId);
    LocationNode rootNode = mapLocation(rootLocation, null, null);

    Set<String> emittedIds = new HashSet<>();
    emittedIds.add(rootNode.getId());

    int nodeCount = 1;
    int deepestDepth = 0;
    boolean truncated = false;
    List<LocationNode> currentLevel = new ArrayList<>(List.of(rootNode));

    for (int currentDepth = 0; !currentLevel.isEmpty(); currentDepth++) {
      sortNodesById(currentLevel);
      if (currentDepth >= config.getMaxDepth() || nodeCount >= config.getMaxNodes()) {
        markHasMoreChildren(currentLevel);
        truncated = true;
        break;
      }

      List<LocationNode> nextLevel = new ArrayList<>();
      boolean stopTraversal = false;

      for (int batchStart = 0;
          batchStart < currentLevel.size() && !stopTraversal;
          batchStart += config.getMaxPartOfBatchSize()) {
        int batchEnd = Math.min(batchStart + config.getMaxPartOfBatchSize(), currentLevel.size());
        List<LocationNode> parentBatch = currentLevel.subList(batchStart, batchEnd);
        Map<String, List<LocationNode>> childrenByParent =
            fetchChildrenForBatch(client, parentBatch, emittedIds);

        for (int parentIndex = batchStart; parentIndex < batchEnd; parentIndex++) {
          LocationNode parent = currentLevel.get(parentIndex);
          String parentId = parent.getId();
          List<LocationNode> children = childrenByParent.getOrDefault(parentId, List.of());
          if (children.isEmpty()) {
            continue;
          }

          if (nodeCount + children.size() > config.getMaxNodes()) {
            parent.setHasMoreChildren(true);
            markHasMoreChildren(currentLevel.subList(parentIndex + 1, currentLevel.size()));
            markHasMoreChildren(nextLevel);
            truncated = true;
            stopTraversal = true;
            break;
          }

          parent.getChildren().addAll(children);
          for (LocationNode child : children) {
            emittedIds.add(child.getId());
          }
          nextLevel.addAll(children);
          nodeCount += children.size();
          deepestDepth = currentDepth + 1;
        }
      }

      if (stopTraversal) {
        break;
      }
      currentLevel = nextLevel;
    }

    LocationHierarchyMeta meta = new LocationHierarchyMeta();
    meta.setNodeCount(nodeCount);
    meta.setDepth(deepestDepth);
    meta.setTruncated(truncated);
    meta.setBuiltAt(Instant.now());

    LocationHierarchy hierarchy = new LocationHierarchy();
    hierarchy.setRoot(rootNode);
    hierarchy.setMeta(meta);
    return hierarchy;
  }

  private void markHasMoreChildren(List<LocationNode> nodes) {
    for (LocationNode node : nodes) {
      node.setHasMoreChildren(true);
    }
  }

  private Location readRootLocation(IGenericClient client, String rootId) {
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

  // Per-level Location.partOf search
  Map<String, List<LocationNode>> fetchChildrenForBatch(
      IGenericClient client, List<LocationNode> parents, Set<String> emittedIds) {
    Objects.requireNonNull(parents, "parents cannot be null");
    if (parents.isEmpty()) {
      return new HashMap<>();
    }

    List<String> parentIds = sortedParentLogicalIds(parents);
    Set<String> parentIdSet = new HashSet<>(parentIds);
    Map<String, String> parentDisplayById = parentDisplayById(parents);
    Map<String, List<LocationNode>> childrenByParent = new HashMap<>();
    Map<String, String> parentByChildId = new HashMap<>(); // Tracks the first parent per child.
    Set<String> visitedNextUrls = new HashSet<>();

    Bundle page = searchChildrenForParentIds(client, parentIds);
    while (true) {
      validateSearchBundle(page);
      collectChildrenFromSearchPage(
          page, parentIdSet, parentDisplayById, childrenByParent, parentByChildId, emittedIds);

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
    for (List<LocationNode> children : childrenByParent.values()) {
      sortNodesById(children);
    }
    return childrenByParent;
  }

  private Bundle searchChildrenForParentIds(IGenericClient client, List<String> parentIds) {
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

  private void collectChildrenFromSearchPage(
      Bundle page,
      Set<String> parentIds,
      Map<String, String> parentDisplayById,
      Map<String, List<LocationNode>> childrenByParent,
      Map<String, String> parentByChildId,
      Set<String> emittedIds) {
    for (Bundle.BundleEntryComponent entry : page.getEntry()) {
      Resource resource = entry.getResource();
      if (resource == null) {
        throw new LocationHierarchyUpstreamException(
            "FHIR child search returned a missing resource");
      }

      Bundle.SearchEntryMode entryMode = entry.getSearch().getMode();
      if (resource instanceof OperationOutcome) {
        handleOutcomeEntry((OperationOutcome) resource, entryMode);
        continue;
      }
      if (entryMode == Bundle.SearchEntryMode.OUTCOME) {
        throw new LocationHierarchyUpstreamException(
            "FHIR child search returned a non-OperationOutcome outcome entry");
      }
      if (entryMode == Bundle.SearchEntryMode.INCLUDE) {
        throw new LocationHierarchyUpstreamException(
            "FHIR child search returned an include Location entry");
      }
      if (!(resource instanceof Location)) {
        throw new LocationHierarchyUpstreamException(
            "FHIR child search returned a non-Location resource: " + resource.fhirType());
      }

      processChildLocation(
          (Location) resource,
          parentIds,
          parentDisplayById,
          childrenByParent,
          parentByChildId,
          emittedIds);
    }
  }

  private void handleOutcomeEntry(OperationOutcome outcome, Bundle.SearchEntryMode entryMode) {
    if (entryMode != Bundle.SearchEntryMode.OUTCOME) {
      throw new LocationHierarchyUpstreamException(
          "FHIR child search returned OperationOutcome as a match/include entry");
    }

    for (OperationOutcome.OperationOutcomeIssueComponent issue : outcome.getIssue()) {
      OperationOutcome.IssueSeverity severity = issue.getSeverity();
      if (severity == OperationOutcome.IssueSeverity.FATAL
          || severity == OperationOutcome.IssueSeverity.ERROR) {
        throw new LocationHierarchyUpstreamException(
            "FHIR child search returned an error OperationOutcome");
      }
    }

    logger.warn("Ignoring non-error OperationOutcome returned by FHIR child search");
  }

  private void processChildLocation(
      Location location,
      Set<String> parentIds,
      Map<String, String> parentDisplayById,
      Map<String, List<LocationNode>> childrenByParent,
      Map<String, String> parentByChildId,
      Set<String> emittedIds) {
    String childId = normalizeLocationIdForEdge(location);
    if (childId == null) {
      logSkippedEdge(null, null, "missing child id");
      return;
    }

    String parentId = normalizeParentIdForEdge(location.getPartOf());
    if (parentId == null) {
      logSkippedEdge(childId, null, "missing or malformed parent reference");
      return;
    }
    if (!parentIds.contains(parentId)) {
      logSkippedEdge(childId, parentId, "parent outside current batch");
      return;
    }
    if (childId.equals(parentId)) {
      logSkippedEdge(childId, parentId, "self-parent edge");
      return;
    }
    if (emittedIds.contains(childId)) {
      logSkippedEdge(childId, parentId, "child already emitted");
      return;
    }

    String previousParentId = parentByChildId.putIfAbsent(childId, parentId);
    if (previousParentId != null) {
      if (!previousParentId.equals(parentId)) {
        throw new LocationHierarchyUpstreamException(
            "FHIR child search returned conflicting parents for Location/" + childId);
      }
      return;
    }

    childrenByParent
        .computeIfAbsent(parentId, childParentId -> new ArrayList<>())
        .add(
            mapLocation(
                location, canonicalLocationReference(parentId), parentDisplayById.get(parentId)));
  }

  private @Nullable String normalizeLocationIdForEdge(Location location) {
    try {
      return normalizeLocationId(location);
    } catch (LocationHierarchyUpstreamException e) {
      return null;
    }
  }

  private @Nullable String normalizeParentIdForEdge(Reference partOf) {
    if (partOf == null || partOf.isEmpty()) {
      return null;
    }

    IIdType parentReference = partOf.getReferenceElement();
    if (parentReference == null || parentReference.isEmpty() || parentReference.isLocal()) {
      return null;
    }
    if (!"Location".equals(parentReference.getResourceType())) {
      return null;
    }

    String parentId = parentReference.toUnqualifiedVersionless().getIdPart();
    return parentId == null || parentId.isBlank() ? null : parentId;
  }

  private void logSkippedEdge(@Nullable String childId, @Nullable String parentId, String reason) {
    logger.warn(
        "Skipping Location hierarchy edge: childId={}, parentId={}, reason={}",
        childId,
        parentId,
        reason);
  }

  private void sortNodesById(List<LocationNode> nodes) {
    if (nodes.size() > 1) {
      nodes.sort(Comparator.comparing(LocationNode::getId));
    }
  }

  private List<String> sortedParentLogicalIds(List<LocationNode> parents) {
    List<String> parentIds = new ArrayList<>(parents.size());
    for (LocationNode parent : parents) {
      parentIds.add(parent.getId());
    }
    // The FHIR search is semantically order-independent, but sorting keeps the generated batched
    // request stable across JVM collection iteration order, which makes behavior easier to test,
    // log, compare, and troubleshoot.
    Collections.sort(parentIds);
    return parentIds;
  }

  private Map<String, String> parentDisplayById(List<LocationNode> parents) {
    Map<String, String> result = new HashMap<>();
    for (LocationNode parent : parents) {
      if (parent.getName() != null) {
        result.put(parent.getId(), parent.getName());
      }
    }
    return result;
  }

  private LocationNode mapLocation(
      Location location, @Nullable String canonicalPartOf, @Nullable String partOfDisplay) {
    String logicalId = normalizeLocationId(location);

    LocationNode node = new LocationNode();
    node.setId(logicalId);
    node.setName(location.hasName() ? location.getName() : null);
    node.setStatus(location.hasStatus() ? location.getStatus().toCode() : null);
    node.setDescription(location.hasDescription() ? location.getDescription() : null);
    node.setPhysicalType(toFhirCodeableConcept(location.getPhysicalType()));
    node.setType(toFhirCodeableConcepts(location.getType()));
    node.setPartOf(toFhirReference(canonicalPartOf, partOfDisplay));
    return node;
  }

  private @Nullable FhirReference toFhirReference(
      @Nullable String reference, @Nullable String display) {
    if (reference == null && display == null) {
      return null;
    }
    FhirReference fhirReference = new FhirReference();
    fhirReference.setReference(reference);
    fhirReference.setDisplay(display);
    return fhirReference;
  }

  private @Nullable FhirCodeableConcept toFhirCodeableConcept(CodeableConcept concept) {
    if (concept == null || concept.isEmpty()) {
      return null;
    }
    List<FhirCoding> coding = toFhirCodings(concept.getCoding());
    if (coding.isEmpty()) {
      return null;
    }
    FhirCodeableConcept dto = new FhirCodeableConcept();
    dto.setCoding(coding);
    return dto;
  }

  private List<FhirCodeableConcept> toFhirCodeableConcepts(List<CodeableConcept> concepts) {
    List<FhirCodeableConcept> result = new ArrayList<>();
    for (CodeableConcept concept : concepts) {
      FhirCodeableConcept dto = toFhirCodeableConcept(concept);
      if (dto != null) {
        result.add(dto);
      }
    }
    return result;
  }

  private List<FhirCoding> toFhirCodings(List<Coding> codings) {
    List<FhirCoding> result = new ArrayList<>();
    for (Coding coding : codings) {
      if (coding == null || coding.isEmpty()) {
        continue;
      }
      FhirCoding dto = new FhirCoding();
      dto.setSystem(coding.hasSystem() ? coding.getSystem() : null);
      dto.setCode(coding.hasCode() ? coding.getCode() : null);
      dto.setDisplay(coding.hasDisplay() ? coding.getDisplay() : null);
      result.add(dto);
    }
    return result;
  }

  private String normalizeLocationId(Location location) {
    String logicalId = location.getIdElement().toUnqualifiedVersionless().getIdPart();
    if (logicalId == null || logicalId.isBlank()) {
      throw new LocationHierarchyUpstreamException("Location is missing a logical id");
    }
    return logicalId;
  }

  private String canonicalLocationReference(String logicalId) {
    return "Location/" + logicalId;
  }
}
