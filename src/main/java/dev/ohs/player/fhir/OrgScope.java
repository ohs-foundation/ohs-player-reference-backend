package dev.ohs.player.fhir;

import java.util.Set;
import java.util.stream.Collectors;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * The organizations a caller may see data for, already expanded to include descendant
 * organizations.
 *
 * <p>An empty, non-exempt scope denies everything. An exempt scope bypasses org scoping entirely
 * (role checks still apply). Instances are immutable.
 */
@EqualsAndHashCode
@ToString
public final class OrgScope {

  private static final OrgScope DENY_ALL = new OrgScope(Set.of(), false);
  private static final OrgScope EXEMPT = new OrgScope(Set.of(), true);

  private final Set<String> organizationIds;
  private final boolean exempt;

  private OrgScope(Set<String> organizationIds, boolean exempt) {
    this.organizationIds = Set.copyOf(organizationIds);
    this.exempt = exempt;
  }

  public static OrgScope denyAll() {
    return DENY_ALL;
  }

  public static OrgScope exempt() {
    return EXEMPT;
  }

  /**
   * Returns a scope of the given organizations; an empty set gives {@link #denyAll()}.
   *
   * @param organizationIds bare Organization ids (no {@code Organization/} prefix)
   */
  public static OrgScope of(Set<String> organizationIds) {
    return organizationIds.isEmpty() ? DENY_ALL : new OrgScope(organizationIds, false);
  }

  /** Bare Organization ids in scope; empty for an exempt or deny-all scope. */
  public Set<String> organizationIds() {
    return organizationIds;
  }

  /** Whether org scoping is bypassed for this caller. */
  public boolean isExempt() {
    return exempt;
  }

  /** Whether this scope grants nothing, i.e. it is not exempt and has no organizations. */
  public boolean isEmpty() {
    return !exempt && organizationIds.isEmpty();
  }

  /**
   * The single comma-joined (OR) value to send for {@code searchParam}. {@code _id} takes bare ids;
   * every reference parameter takes {@code Organization/<id>}. Ids are sorted so the value is
   * stable across requests.
   */
  public String filterValue(String searchParam) {
    String prefix = "_id".equals(searchParam) ? "" : "Organization/";
    return organizationIds.stream()
        .sorted()
        .map(id -> prefix + id)
        .collect(Collectors.joining(","));
  }
}
