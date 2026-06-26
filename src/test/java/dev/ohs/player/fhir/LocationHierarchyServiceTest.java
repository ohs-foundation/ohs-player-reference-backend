package dev.ohs.player.fhir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.api.SearchStyleEnum;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import ca.uhn.fhir.rest.gclient.ICriterion;
import ca.uhn.fhir.rest.gclient.ICriterionInternal;
import ca.uhn.fhir.rest.gclient.IGetPage;
import ca.uhn.fhir.rest.gclient.IGetPageTyped;
import ca.uhn.fhir.rest.gclient.IQuery;
import ca.uhn.fhir.rest.gclient.IRead;
import ca.uhn.fhir.rest.gclient.IReadExecutable;
import ca.uhn.fhir.rest.gclient.IReadTyped;
import ca.uhn.fhir.rest.gclient.IUntypedQuery;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

class LocationHierarchyServiceTest {

  @Test
  void getLocationHierarchy_BuildsOnceThenReturnsCachedHierarchy() {
    TestLocationHierarchyService service = service(this::hierarchy);

    LocationHierarchy first = service.getLocationHierarchy("rootID");
    LocationHierarchy second = service.getLocationHierarchy("rootID");

    assertSame(first, second);
    assertEquals(1, service.buildCount());
  }

  @Test
  void getLocationHierarchy_DoesNotCacheBuildFailures() {
    TestLocationHierarchyService service =
        service(
            rootId -> {
              throw new LocationHierarchyUpstreamException("FHIR unavailable");
            });

    assertThrows(
        LocationHierarchyUpstreamException.class, () -> service.getLocationHierarchy("root"));
    assertThrows(
        LocationHierarchyUpstreamException.class, () -> service.getLocationHierarchy("root"));
    assertEquals(2, service.buildCount());
  }

  @ParameterizedTest
  @MethodSource("incompleteBuilds")
  void getLocationHierarchy_RejectsAndDoesNotCacheIncompleteBuilds(
      LocationHierarchy incompleteHierarchy) {
    assertInvalidBuildIsNotCached(incompleteHierarchy);
  }

  @Test
  void buildHierarchy_ReturnsRootOnlyTreeWhenRootHasNoChildren() {
    Location root = new Location().setName("Root Location").setPartOf(new Reference("Location/"));
    root.setId("Location/root-id/_history/3");
    LocationHierarchyService service = realService(root);

    LocationHierarchy hierarchy = service.getLocationHierarchy("root-id");

    assertEquals("Location/root-id", hierarchy.getRoot().getId());
    assertEquals("Root Location", hierarchy.getRoot().getName());
    assertNull(hierarchy.getRoot().getPartOf());
    assertTrue(hierarchy.getRoot().getChildren().isEmpty());
    assertEquals(1, hierarchy.getMeta().getNodeCount());
    assertEquals(0, hierarchy.getMeta().getDepth());
    assertFalse(hierarchy.getMeta().isTruncated());
    assertTrue(hierarchy.getMeta().getBuiltAt() != null);
  }

  @Test
  void buildHierarchy_ReturnsChildrenAndGrandchildrenInStableOrder() {
    Location root = location("root-id", null, "Root");
    LocationHierarchyService service =
        realService(
            root,
            searchsetBundle(
                location("region-b", "Location/root-id", "Region B"),
                location("region-a", "Location/root-id", "Region A")),
            searchsetBundle(
                location("district-a", "Location/region-a", "District A"),
                location("district-b", "Location/region-b", "District B")),
            searchsetBundle());

    LocationHierarchy hierarchy = service.getLocationHierarchy("root-id");

    LocationNode rootNode = hierarchy.getRoot();
    assertEquals(2, rootNode.getChildren().size());
    assertEquals("Location/region-a", rootNode.getChildren().get(0).getId());
    assertEquals("Location/region-b", rootNode.getChildren().get(1).getId());
    assertEquals(1, rootNode.getChildren().get(0).getChildren().size());
    assertEquals("Location/district-a", rootNode.getChildren().get(0).getChildren().get(0).getId());
    assertEquals(1, rootNode.getChildren().get(1).getChildren().size());
    assertEquals("Location/district-b", rootNode.getChildren().get(1).getChildren().get(0).getId());
    assertEquals(5, hierarchy.getMeta().getNodeCount());
    assertEquals(2, hierarchy.getMeta().getDepth());
    assertFalse(hierarchy.getMeta().isTruncated());
  }

  @Test
  void buildHierarchy_StopsBeforeFetchingChildrenBeyondMaxDepth() {
    LocationHierarchyService service =
        realService(
            location("root-id", null, "Root"),
            new LocationHierarchyConfig(100, 200, 1, 10_000),
            searchsetBundle(location("region-a", "Location/root-id", "Region A")));

    LocationHierarchy hierarchy = service.getLocationHierarchy("root-id");

    LocationNode region = hierarchy.getRoot().getChildren().get(0);
    assertTrue(region.isHasMoreChildren());
    assertTrue(region.getChildren().isEmpty());
    assertEquals(2, hierarchy.getMeta().getNodeCount());
    assertEquals(1, hierarchy.getMeta().getDepth());
    assertTrue(hierarchy.getMeta().isTruncated());
  }

  @ParameterizedTest
  @MethodSource("rootOnlyTruncationConfigs")
  void buildHierarchy_ReturnsOnlyRootWhenLimitsPreventChildTraversal(
      LocationHierarchyConfig config) {
    RealServiceFixture fixture =
        realServiceFixture(location("root-id", null, "Root"), config, searchsetBundle());

    LocationHierarchy hierarchy = fixture.service.getLocationHierarchy("root-id");

    assertTrue(hierarchy.getRoot().getChildren().isEmpty());
    assertTrue(hierarchy.getRoot().isHasMoreChildren());
    assertEquals(1, hierarchy.getMeta().getNodeCount());
    assertEquals(0, hierarchy.getMeta().getDepth());
    assertTrue(hierarchy.getMeta().isTruncated());
    verify(fixture.client, never()).search();
  }

  @Test
  void buildHierarchy_StopsAfterNodeLimitIsReached() {
    RealServiceFixture fixture =
        realServiceFixture(
            location("root-id", null, "Root"),
            new LocationHierarchyConfig(100, 200, 25, 5),
            searchsetBundle(
                location("region-a", "Location/root-id", "Region A"),
                location("region-b", "Location/root-id", "Region B")),
            searchsetBundle(
                location("district-a", "Location/region-a", "District A"),
                location("district-b1", "Location/region-b", "District B1"),
                location("district-b2", "Location/region-b", "District B2")),
            searchsetBundle(location("facility-a", "Location/district-a", "Facility A")));

    LocationHierarchy hierarchy = fixture.service.getLocationHierarchy("root-id");

    LocationNode regionA = hierarchy.getRoot().getChildren().get(0);
    LocationNode regionB = hierarchy.getRoot().getChildren().get(1);
    LocationNode districtA = regionA.getChildren().get(0);
    assertEquals("Location/district-a", districtA.getId());
    assertTrue(districtA.isHasMoreChildren());
    assertTrue(districtA.getChildren().isEmpty());
    assertTrue(regionB.isHasMoreChildren());
    assertEquals(4, hierarchy.getMeta().getNodeCount());
    assertEquals(2, hierarchy.getMeta().getDepth());
    assertTrue(hierarchy.getMeta().isTruncated());
    verify(fixture.query, times(2)).execute();
  }

  @Test
  void buildHierarchy_IncludesAllChildrenWhenNodeLimitExactlyFits() {
    LocationHierarchyService service =
        realService(
            location("root-id", null, "Root"),
            new LocationHierarchyConfig(100, 200, 25, 3),
            searchsetBundle(
                location("region-a", "Location/root-id", "Region A"),
                location("region-b", "Location/root-id", "Region B")));

    LocationHierarchy hierarchy = service.getLocationHierarchy("root-id");

    assertEquals(2, hierarchy.getRoot().getChildren().size());
    assertEquals("Location/region-a", hierarchy.getRoot().getChildren().get(0).getId());
    assertEquals("Location/region-b", hierarchy.getRoot().getChildren().get(1).getId());
    assertTrue(hierarchy.getRoot().getChildren().get(0).isHasMoreChildren());
    assertTrue(hierarchy.getRoot().getChildren().get(1).isHasMoreChildren());
    assertEquals(3, hierarchy.getMeta().getNodeCount());
    assertEquals(1, hierarchy.getMeta().getDepth());
    assertTrue(hierarchy.getMeta().isTruncated());
  }

  @Test
  void buildHierarchy_SearchesParentsInConfiguredBatchSizes() {
    LocationHierarchyService service =
        realService(
            location("root-id", null, "Root"),
            new LocationHierarchyConfig(1, 200, 25, 10_000),
            searchsetBundle(
                location("region-a", "Location/root-id", "Region A"),
                location("region-b", "Location/root-id", "Region B")),
            searchsetBundle(location("district-a", "Location/region-a", "District A")),
            searchsetBundle(location("district-b", "Location/region-b", "District B")),
            searchsetBundle(),
            searchsetBundle());

    LocationHierarchy hierarchy = service.getLocationHierarchy("root-id");

    LocationNode regionA = hierarchy.getRoot().getChildren().get(0);
    LocationNode regionB = hierarchy.getRoot().getChildren().get(1);
    assertEquals(1, regionA.getChildren().size());
    assertEquals("Location/district-a", regionA.getChildren().get(0).getId());
    assertEquals(1, regionB.getChildren().size());
    assertEquals("Location/district-b", regionB.getChildren().get(0).getId());
    assertEquals(5, hierarchy.getMeta().getNodeCount());
    assertEquals(2, hierarchy.getMeta().getDepth());
    assertFalse(hierarchy.getMeta().isTruncated());
  }

  @Test
  void buildHierarchy_ThrowsNotFoundWhenRootDoesNotExist() {
    LocationHierarchyService service = realService(new ResourceNotFoundException("not found"));

    assertThrows(ResourceNotFoundException.class, () -> service.getLocationHierarchy("missing"));
  }

  @ParameterizedTest
  @MethodSource("rootReadFailures")
  void buildHierarchy_ReportsRootReadFailuresAsUpstreamFailures(RuntimeException failure) {
    assertThrows(
        LocationHierarchyUpstreamException.class,
        () -> realService(failure).getLocationHierarchy("root"));
  }

  @Test
  void fetchChildrenForBatch_SearchesByParentIdsUsingPostAndConfiguredPageSize() {
    SearchClientFixture fixture = searchClient(searchsetBundle());
    LocationHierarchyService service = service();

    Map<String, List<LocationNode>> result =
        service.fetchChildrenForBatch(
            fixture.client,
            List.of(node("Location/parent-b"), node("Location/parent-a")),
            Set.of());

    assertTrue(result.isEmpty());
    ArgumentCaptor<ICriterion> criterionCaptor = ArgumentCaptor.forClass(ICriterion.class);
    verify(fixture.client).search();
    verify(fixture.search).forResource(Location.class);
    verify(fixture.query).where(criterionCaptor.capture());
    verify(fixture.query).count(200);
    verify(fixture.query).usingStyle(SearchStyleEnum.POST);
    verify(fixture.query).returnBundle(Bundle.class);
    verify(fixture.query).execute();

    ICriterionInternal criterion = (ICriterionInternal) criterionCaptor.getValue();
    assertEquals("partof", criterion.getParameterName());
    assertEquals("parent-a,parent-b", criterion.getParameterValue(FhirContext.forR4()));
  }

  @Test
  void fetchChildrenForBatch_GroupsChildrenUnderTheirParents() {
    Bundle bundle = searchsetBundle();
    bundle.addEntry().setResource(location("child-b", "Location/parent-a", "Child B"));
    bundle
        .addEntry()
        .setResource(
            location("Location/child-a/_history/2", "Location/parent-a/_history/4", "Child A"));
    bundle.addEntry().setResource(location("child-c", "Location/parent-b", "Child C"));
    LocationHierarchyService service = service();

    Map<String, List<LocationNode>> result =
        service.fetchChildrenForBatch(
            searchClient(bundle).client,
            List.of(node("Location/parent-b"), node("Location/parent-a")),
            Set.of());

    assertEquals(2, result.get("parent-a").size());
    assertEquals("Location/child-a", result.get("parent-a").get(0).getId());
    assertEquals("Location/parent-a", result.get("parent-a").get(0).getPartOf());
    assertEquals("Child A", result.get("parent-a").get(0).getName());
    assertEquals("Location/child-b", result.get("parent-a").get(1).getId());
    assertEquals(1, result.get("parent-b").size());
    assertEquals("Location/child-c", result.get("parent-b").get(0).getId());
  }

  @ParameterizedTest
  @EnumSource(
      value = OperationOutcome.IssueSeverity.class,
      names = {"INFORMATION", "WARNING"})
  void fetchChildrenForBatch_IgnoresNonErrorOutcomeEntries(
      OperationOutcome.IssueSeverity severity) {
    Bundle bundle = searchsetBundle();
    bundle
        .addEntry()
        .setResource(outcome(severity))
        .getSearch()
        .setMode(Bundle.SearchEntryMode.OUTCOME);
    bundle.addEntry().setResource(location("child-a", "Location/parent-a", "Child A"));
    LocationHierarchyService service = service();

    Map<String, List<LocationNode>> result =
        service.fetchChildrenForBatch(
            searchClient(bundle).client, List.of(node("Location/parent-a")), Set.of());

    assertEquals(1, result.get("parent-a").size());
  }

  @ParameterizedTest
  @EnumSource(
      value = OperationOutcome.IssueSeverity.class,
      names = {"ERROR", "FATAL"})
  void fetchChildrenForBatch_RejectsErrorOutcomeEntries(OperationOutcome.IssueSeverity severity) {
    Bundle bundle = searchsetBundle();
    bundle
        .addEntry()
        .setResource(outcome(severity))
        .getSearch()
        .setMode(Bundle.SearchEntryMode.OUTCOME);
    LocationHierarchyService service = service();

    assertThrows(
        LocationHierarchyUpstreamException.class,
        () ->
            service.fetchChildrenForBatch(
                searchClient(bundle).client, List.of(node("Location/parent-a")), Set.of()));
  }

  @Test
  void fetchChildrenForBatch_RejectsUnexpectedSearchResultEntries() {
    Bundle missingResource = searchsetBundle();
    missingResource.addEntry();
    LocationHierarchyService service = service();

    assertThrows(
        LocationHierarchyUpstreamException.class,
        () ->
            service.fetchChildrenForBatch(
                searchClient(missingResource).client,
                List.of(node("Location/parent-a")),
                Set.of()));

    Bundle outcomeAsMatch = searchsetBundle();
    outcomeAsMatch.addEntry().setResource(outcome(OperationOutcome.IssueSeverity.WARNING));
    assertThrows(
        LocationHierarchyUpstreamException.class,
        () ->
            service.fetchChildrenForBatch(
                searchClient(outcomeAsMatch).client, List.of(node("Location/parent-a")), Set.of()));

    Bundle includeLocation = searchsetBundle();
    includeLocation
        .addEntry()
        .setResource(location("child-a", "Location/parent-a", "Child"))
        .getSearch()
        .setMode(Bundle.SearchEntryMode.INCLUDE);
    assertThrows(
        LocationHierarchyUpstreamException.class,
        () ->
            service.fetchChildrenForBatch(
                searchClient(includeLocation).client,
                List.of(node("Location/parent-a")),
                Set.of()));
  }

  @Test
  void fetchChildrenForBatch_SkipsMalformedEdgesAndAlreadyEmittedChildren() {
    Bundle bundle = searchsetBundle();
    bundle.addEntry().setResource(location(null, "Location/parent-a", "Missing Id"));
    bundle.addEntry().setResource(location("missing-parent", null, "Missing Parent"));
    bundle.addEntry().setResource(location("external-parent", "Location/other-parent", "External"));
    bundle.addEntry().setResource(location("self-parent", "Location/self-parent", "Self"));
    bundle
        .addEntry()
        .setResource(location("already-emitted", "Location/parent-a", "Already Emitted"));
    bundle
        .addEntry()
        .setResource(location("non-location-parent", "Organization/org-a", "Wrong Parent"));
    bundle.addEntry().setResource(location("valid-child", "Location/parent-a", "Valid"));
    LocationHierarchyService service = service();

    Map<String, List<LocationNode>> result =
        service.fetchChildrenForBatch(
            searchClient(bundle).client,
            List.of(node("Location/parent-a"), node("Location/self-parent")),
            Set.of("already-emitted"));

    assertEquals(1, result.get("parent-a").size());
    assertEquals("Location/valid-child", result.get("parent-a").get(0).getId());
  }

  @Test
  void fetchChildrenForBatch_DeduplicatesSameChildUnderSameParent() {
    Bundle bundle = searchsetBundle();
    bundle.addEntry().setResource(location("child-a", "Location/parent-a", "First"));
    bundle
        .addEntry()
        .setResource(location("Location/child-a/_history/2", "Location/parent-a", "Duplicate"));
    LocationHierarchyService service = service();

    Map<String, List<LocationNode>> result =
        service.fetchChildrenForBatch(
            searchClient(bundle).client, List.of(node("Location/parent-a")), Set.of());

    assertEquals(1, result.get("parent-a").size());
    assertEquals("First", result.get("parent-a").get(0).getName());
  }

  @Test
  void fetchChildrenForBatch_RejectsConflictingParentsForSameChild() {
    Bundle bundle = searchsetBundle();
    bundle.addEntry().setResource(location("child-a", "Location/parent-a", "Child"));
    bundle.addEntry().setResource(location("child-a", "Location/parent-b", "Child"));
    LocationHierarchyService service = service();

    assertThrows(
        LocationHierarchyUpstreamException.class,
        () ->
            service.fetchChildrenForBatch(
                searchClient(bundle).client,
                List.of(node("Location/parent-a"), node("Location/parent-b")),
                Set.of()));
  }

  @Test
  void fetchChildrenForBatch_FollowsNextPages() {
    Bundle firstPage = searchsetBundle();
    firstPage.addLink().setRelation("next").setUrl("http://fhir.example/fhir?_getpages=next");
    Bundle secondPage = searchsetBundle();
    SearchClientFixture fixture = searchClient(firstPage);
    when(fixture.client.loadPage()).thenReturn(fixture.getPage);
    when(fixture.getPage.next(firstPage)).thenReturn(fixture.getPageTyped);
    when(fixture.getPageTyped.execute()).thenReturn(secondPage);
    LocationHierarchyService service = service();

    Map<String, List<LocationNode>> result =
        service.fetchChildrenForBatch(fixture.client, List.of(node("Location/parent-a")), Set.of());

    assertTrue(result.isEmpty());
    verify(fixture.client).loadPage();
    verify(fixture.getPage).next(firstPage);
    verify(fixture.getPageTyped).execute();
  }

  @Test
  void fetchChildrenForBatch_RejectsRepeatedNextLink() {
    Bundle firstPage = searchsetBundle();
    firstPage.addLink().setRelation("next").setUrl("http://fhir.example/fhir?_getpages=same");
    Bundle secondPage = searchsetBundle();
    secondPage.addLink().setRelation("next").setUrl("http://fhir.example/fhir?_getpages=same");
    SearchClientFixture fixture = searchClient(firstPage);
    when(fixture.client.loadPage()).thenReturn(fixture.getPage);
    when(fixture.getPage.next(firstPage)).thenReturn(fixture.getPageTyped);
    when(fixture.getPageTyped.execute()).thenReturn(secondPage);
    LocationHierarchyService service = service();

    assertThrows(
        LocationHierarchyUpstreamException.class,
        () ->
            service.fetchChildrenForBatch(
                fixture.client, List.of(node("Location/parent-a")), Set.of()));
  }

  @Test
  void fetchChildrenForBatch_RejectsNonSearchsetBundle() {
    Bundle collection = new Bundle();
    collection.setType(Bundle.BundleType.COLLECTION);
    LocationHierarchyService service = service();

    assertThrows(
        LocationHierarchyUpstreamException.class,
        () ->
            service.fetchChildrenForBatch(
                searchClient(collection).client, List.of(node("Location/parent-a")), Set.of()));
  }

  @Test
  void fetchChildrenForBatch_WrapsInitialSearchFailure() {
    SearchClientFixture fixture = searchClient(searchsetBundle());
    when(fixture.query.execute()).thenThrow(new InternalErrorException("server error"));
    LocationHierarchyService service = service();

    assertThrows(
        LocationHierarchyUpstreamException.class,
        () ->
            service.fetchChildrenForBatch(
                fixture.client, List.of(node("Location/parent-a")), Set.of()));
  }

  @Test
  void fetchChildrenForBatch_WrapsPagingFailure() {
    Bundle firstPage = searchsetBundle();
    firstPage.addLink().setRelation("next").setUrl("http://fhir.example/fhir?_getpages=next");
    SearchClientFixture fixture = searchClient(firstPage);
    when(fixture.client.loadPage()).thenReturn(fixture.getPage);
    when(fixture.getPage.next(firstPage)).thenReturn(fixture.getPageTyped);
    when(fixture.getPageTyped.execute()).thenThrow(new InternalErrorException("server error"));
    LocationHierarchyService service = service();

    assertThrows(
        LocationHierarchyUpstreamException.class,
        () ->
            service.fetchChildrenForBatch(
                fixture.client, List.of(node("Location/parent-a")), Set.of()));
  }

  private TestLocationHierarchyService service(Function<String, LocationHierarchy> builder) {
    return new TestLocationHierarchyService(builder);
  }

  private LocationHierarchyService service() {
    return new LocationHierarchyService(cache(), fhirContext(), FHIR_SERVER_URL, config());
  }

  private static final String FHIR_SERVER_URL = "http://fhir.example/fhir";

  private static Stream<LocationHierarchy> incompleteBuilds() {
    LocationHierarchy missingRoot = new LocationHierarchy();

    LocationHierarchy missingMetadata = new LocationHierarchy();
    missingMetadata.setRoot(staticNode("rootID"));

    LocationHierarchy invalidNodeCount = staticHierarchy("rootID");
    invalidNodeCount.getMeta().setNodeCount(0);

    LocationHierarchy missingBuiltAt = staticHierarchy("rootID");
    missingBuiltAt.getMeta().setBuiltAt(null);

    return Stream.of(null, missingRoot, missingMetadata, invalidNodeCount, missingBuiltAt);
  }

  private static Stream<LocationHierarchyConfig> rootOnlyTruncationConfigs() {
    return Stream.of(
        new LocationHierarchyConfig(100, 200, 0, 10_000),
        new LocationHierarchyConfig(100, 200, 25, 1));
  }

  private static Stream<RuntimeException> rootReadFailures() {
    return Stream.of(
        new FhirClientConnectionException("connection failed"),
        new InternalErrorException("server error"),
        new DataFormatException("bad payload"));
  }

  private Cache<String, LocationHierarchy> cache() {
    return Caffeine.newBuilder().build();
  }

  private FhirContext fhirContext() {
    return mock(FhirContext.class);
  }

  private LocationHierarchyConfig config() {
    return new LocationHierarchyConfig(100, 200, 25, 10_000);
  }

  private void assertInvalidBuildIsNotCached(LocationHierarchy hierarchy) {
    TestLocationHierarchyService service = service(rootId -> hierarchy);

    assertThrows(IllegalStateException.class, () -> service.getLocationHierarchy("rootID"));
    assertThrows(IllegalStateException.class, () -> service.getLocationHierarchy("rootID"));
    assertEquals(2, service.buildCount());
  }

  private LocationHierarchy hierarchy(String rootId) {
    return staticHierarchy(rootId);
  }

  private static LocationHierarchy staticHierarchy(String rootId) {
    LocationHierarchyMeta meta = new LocationHierarchyMeta();
    meta.setNodeCount(1);
    meta.setBuiltAt(Instant.EPOCH);
    LocationHierarchy hierarchy = new LocationHierarchy();
    hierarchy.setRoot(staticNode(rootId));
    hierarchy.setMeta(meta);
    return hierarchy;
  }

  private LocationNode node(String id) {
    return staticNode(id);
  }

  private static LocationNode staticNode(String id) {
    LocationNode node = new LocationNode();
    node.setId(id);
    return node;
  }

  private LocationHierarchyService realService(Object readOutcome) {
    return realService(readOutcome, config(), searchsetBundle());
  }

  private LocationHierarchyService realService(
      Object readOutcome, LocationHierarchyConfig config, Bundle firstSearchBundle) {
    return realServiceFixture(readOutcome, config, firstSearchBundle).service;
  }

  private LocationHierarchyService realService(Object readOutcome, Bundle... searchBundles) {
    return realService(readOutcome, config(), searchBundles);
  }

  private LocationHierarchyService realService(
      Object readOutcome, LocationHierarchyConfig config, Bundle... searchBundles) {
    return realServiceFixture(readOutcome, config, searchBundles).service;
  }

  private RealServiceFixture realServiceFixture(
      Object readOutcome, LocationHierarchyConfig config, Bundle... searchBundles) {
    FhirContext fhirContext = mock(FhirContext.class);
    IGenericClient client = mock(IGenericClient.class);
    IRead read = mock(IRead.class);
    @SuppressWarnings("unchecked")
    IReadTyped<Location> readTyped = mock(IReadTyped.class);
    @SuppressWarnings("unchecked")
    IReadExecutable<Location> readExecutable = mock(IReadExecutable.class);

    when(fhirContext.newRestfulGenericClient(FHIR_SERVER_URL)).thenReturn(client);
    when(client.read()).thenReturn(read);
    when(read.resource(Location.class)).thenReturn(readTyped);
    when(readTyped.withId("root-id")).thenReturn(readExecutable);
    when(readTyped.withId("missing")).thenReturn(readExecutable);
    when(readTyped.withId("root")).thenReturn(readExecutable);
    if (readOutcome instanceof RuntimeException) {
      when(readExecutable.execute()).thenThrow((RuntimeException) readOutcome);
    } else {
      when(readExecutable.execute()).thenReturn((Location) readOutcome);
    }

    SearchClientFixture searchFixture = configureSearchClient(client, searchBundles);

    LocationHierarchyService service =
        new LocationHierarchyService(cache(), fhirContext, FHIR_SERVER_URL, config);
    return new RealServiceFixture(service, client, searchFixture.query);
  }

  private Bundle searchsetBundle() {
    Bundle bundle = new Bundle();
    bundle.setType(Bundle.BundleType.SEARCHSET);
    return bundle;
  }

  private Bundle searchsetBundle(Location... locations) {
    Bundle bundle = searchsetBundle();
    for (Location location : locations) {
      bundle.addEntry().setResource(location);
    }
    return bundle;
  }

  private Location location(String id, String parentReference, String name) {
    Location location = new Location();
    if (id != null) {
      location.setId(id);
    }
    if (parentReference != null) {
      location.setPartOf(new Reference(parentReference));
    }
    location.setName(name);
    return location;
  }

  private OperationOutcome outcome(OperationOutcome.IssueSeverity severity) {
    OperationOutcome outcome = new OperationOutcome();
    outcome.addIssue().setSeverity(severity);
    return outcome;
  }

  @SuppressWarnings("unchecked")
  private SearchClientFixture searchClient(Bundle firstPage) {
    IGenericClient client = mock(IGenericClient.class);
    return configureSearchClient(client, firstPage);
  }

  @SuppressWarnings("unchecked")
  private SearchClientFixture configureSearchClient(IGenericClient client, Bundle... pages) {
    IUntypedQuery<Bundle> search = mock(IUntypedQuery.class);
    IQuery<Bundle> query = mock(IQuery.class);
    IGetPage getPage = mock(IGetPage.class);
    IGetPageTyped<Bundle> getPageTyped = mock(IGetPageTyped.class);

    doReturn(search).when(client).search();
    when(search.forResource(eq(Location.class))).thenReturn(query);
    when(query.where(any(ICriterion.class))).thenReturn(query);
    when(query.count(anyInt())).thenReturn(query);
    when(query.usingStyle(any())).thenReturn(query);
    when(query.returnBundle(Bundle.class)).thenReturn(query);
    if (pages.length == 0) {
      when(query.execute()).thenReturn(searchsetBundle());
    } else if (pages.length == 1) {
      when(query.execute()).thenReturn(pages[0]);
    } else {
      Bundle[] remainingPages = java.util.Arrays.copyOfRange(pages, 1, pages.length);
      when(query.execute()).thenReturn(pages[0], remainingPages);
    }

    return new SearchClientFixture(client, search, query, getPage, getPageTyped);
  }

  private static class TestLocationHierarchyService extends LocationHierarchyService {
    private final AtomicInteger buildCount = new AtomicInteger();
    private final Function<String, LocationHierarchy> builder;

    TestLocationHierarchyService(Function<String, LocationHierarchy> builder) {
      super(
          Caffeine.newBuilder().<String, LocationHierarchy>build(),
          mock(FhirContext.class),
          FHIR_SERVER_URL,
          new LocationHierarchyConfig(100, 200, 25, 10_000));
      this.builder = builder;
    }

    @Override
    LocationHierarchy buildHierarchy(String rootId) {
      buildCount.incrementAndGet();
      return builder.apply(rootId);
    }

    int buildCount() {
      return buildCount.get();
    }
  }

  private static class SearchClientFixture {
    private final IGenericClient client;
    private final IUntypedQuery<Bundle> search;
    private final IQuery<Bundle> query;
    private final IGetPage getPage;
    private final IGetPageTyped<Bundle> getPageTyped;

    SearchClientFixture(
        IGenericClient client,
        IUntypedQuery<Bundle> search,
        IQuery<Bundle> query,
        IGetPage getPage,
        IGetPageTyped<Bundle> getPageTyped) {
      this.client = client;
      this.search = search;
      this.query = query;
      this.getPage = getPage;
      this.getPageTyped = getPageTyped;
    }
  }

  private static class RealServiceFixture {
    private final LocationHierarchyService service;
    private final IGenericClient client;
    private final IQuery<Bundle> query;

    RealServiceFixture(
        LocationHierarchyService service, IGenericClient client, IQuery<Bundle> query) {
      this.service = service;
      this.client = client;
      this.query = query;
    }
  }
}
