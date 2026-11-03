package dev.ohs.player.fhir;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.hl7.fhir.r4.model.Resource;

/**
 * Adds every descendant organization, following {@code Organization.partOf} downwards with a
 * breadth-first search.
 *
 * <p>Each level issues one {@code Organization?partof=} search per batch of parent ids. Expansion
 * stops quietly at {@code maxDepth} levels below the direct organizations. If the expanded set
 * would exceed {@code maxOrganizations} the expansion fails, so the caller is denied rather than
 * given a silently truncated scope. Already visited ids are never searched again, which makes
 * cycles terminate.
 */
public class PartOfOrganizationScopeExpander implements OrganizationScopeExpander {

  /**
   * Parent ids per {@code partof} search.
   *
   * <p><b>Extension point — batch size.</b> A constant to keep configuration small. To make it
   * operator-settable, add a constructor argument read from an {@code org-scope.*} property in the
   * {@code organizationScopeExpander} bean, the same way the two limits are read.
   */
  static final int BATCH_SIZE = 50;

  private static final int PAGE_SIZE = 200;

  private final int maxDepth;
  private final int maxOrganizations;

  /**
   * @param maxDepth number of {@code partOf} levels to follow below the direct organizations
   * @param maxOrganizations largest expanded scope allowed before expansion fails
   * @throws IllegalArgumentException if either value is less than 1
   */
  public PartOfOrganizationScopeExpander(int maxDepth, int maxOrganizations) {
    if (maxDepth < 1) {
      throw new IllegalArgumentException("org-scope.hierarchy-max-depth must be at least 1");
    }
    if (maxOrganizations < 1) {
      throw new IllegalArgumentException(
          "org-scope.hierarchy-max-organizations must be at least 1");
    }
    this.maxDepth = maxDepth;
    this.maxOrganizations = maxOrganizations;
  }

  @Override
  public Set<String> expand(Set<String> directOrganizationIds, GatewayFhirSearch search) {
    Set<String> result = new LinkedHashSet<>(directOrganizationIds);
    checkSize(result);
    // Sorted so that upstream search URLs are stable across requests.
    List<String> frontier = new ArrayList<>(new TreeSet<>(directOrganizationIds));
    for (int depth = 0; depth < maxDepth && !frontier.isEmpty(); depth++) {
      List<String> next = new ArrayList<>();
      for (int start = 0; start < frontier.size(); start += BATCH_SIZE) {
        List<String> batch = frontier.subList(start, Math.min(start + BATCH_SIZE, frontier.size()));
        for (Resource child : search.searchAll(childrenUrl(batch))) {
          String childId = child.getIdElement().getIdPart();
          if (childId != null && result.add(childId)) {
            checkSize(result);
            next.add(childId);
          }
        }
      }
      frontier = next;
    }
    return Set.copyOf(result);
  }

  private void checkSize(Set<String> result) {
    if (result.size() > maxOrganizations) {
      throw new GatewayFhirSearch.UpstreamException(
          "Organization scope exceeds org-scope.hierarchy-max-organizations ("
              + maxOrganizations
              + ")");
    }
  }

  private static String childrenUrl(List<String> parentIds) {
    List<String> references = new ArrayList<>(parentIds.size());
    for (String id : parentIds) {
      references.add("Organization/" + id);
    }
    return "Organization?partof="
        + URLEncoder.encode(String.join(",", references), StandardCharsets.UTF_8)
        + "&_elements=id&_count="
        + PAGE_SIZE;
  }
}
