package dev.ohs.player.fhir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Resource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PartOfOrganizationScopeExpanderTest {

  @Mock private GatewayFhirSearch search;

  @Test
  void expand_TwoLevels_IncludesAllDescendants() {
    // P -> A -> A1
    childrenOf(List.of("P"), "A");
    childrenOf(List.of("A"), "A1");
    childrenOf(List.of("A1"));

    Set<String> result = new PartOfOrganizationScopeExpander(10, 100).expand(Set.of("P"), search);

    assertEquals(Set.of("P", "A", "A1"), result);
  }

  @Test
  void expand_ManyRoots_BatchesParentIds() {
    List<String> roots =
        IntStream.range(0, PartOfOrganizationScopeExpander.BATCH_SIZE + 1)
            .mapToObj(i -> "r" + i)
            .collect(Collectors.toList());
    when(search.searchAll(anyString())).thenReturn(List.of());

    Set<String> result =
        new PartOfOrganizationScopeExpander(10, 1000).expand(Set.copyOf(roots), search);

    assertEquals(Set.copyOf(roots), result);
    verify(search, times(2)).searchAll(anyString());
  }

  @Test
  void expand_CycleAndSelfParent_Terminates() {
    // A -> B -> A, and A is its own child.
    childrenOf(List.of("A"), "A", "B");
    childrenOf(List.of("B"), "A");

    Set<String> result = new PartOfOrganizationScopeExpander(10, 100).expand(Set.of("A"), search);

    assertEquals(Set.of("A", "B"), result);
    verify(search, times(2)).searchAll(anyString());
  }

  @Test
  void expand_MoreThanMaxOrganizations_Throws() {
    childrenOf(List.of("P"), "A", "B", "C");

    assertThrows(
        GatewayFhirSearch.UpstreamException.class,
        () -> new PartOfOrganizationScopeExpander(10, 3).expand(Set.of("P"), search));
  }

  @Test
  void expand_MaxDepthReached_StopsWithoutThrowing() {
    // P -> A -> A1; depth 1 only reaches A.
    childrenOf(List.of("P"), "A");
    childrenOf(List.of("A"), "A1");

    Set<String> result = new PartOfOrganizationScopeExpander(1, 100).expand(Set.of("P"), search);

    assertEquals(Set.of("P", "A"), result);
    verify(search, times(1)).searchAll(anyString());
  }

  @Test
  void constructor_ValueBelowOne_Throws() {
    assertThrows(IllegalArgumentException.class, () -> new PartOfOrganizationScopeExpander(0, 10));
    assertThrows(IllegalArgumentException.class, () -> new PartOfOrganizationScopeExpander(1, 0));
  }

  @Test
  void directOnly_MakesNoUpstreamCall() {
    Set<String> result = OrganizationScopeExpander.DIRECT_ONLY.expand(Set.of("A"), search);

    assertEquals(Set.of("A"), result);
    verify(search, never()).searchAll(anyString());
  }

  private void childrenOf(List<String> parents, String... children) {
    List<Resource> resources = new ArrayList<>();
    for (String child : children) {
      resources.add(new Organization().setId(child));
    }
    when(search.searchAll(childrenUrl(parents))).thenReturn(resources);
  }

  private static String childrenUrl(List<String> parents) {
    String value =
        parents.stream().map(id -> "Organization/" + id).collect(Collectors.joining(","));
    return "Organization?partof="
        + URLEncoder.encode(value, StandardCharsets.UTF_8)
        + "&_elements=id&_count=200";
  }
}
