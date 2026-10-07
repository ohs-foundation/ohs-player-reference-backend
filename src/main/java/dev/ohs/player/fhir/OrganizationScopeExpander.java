package dev.ohs.player.fhir;

import java.util.Set;

/**
 * Expands a practitioner's directly assigned organizations into the full set they may see.
 *
 * <p><b>Extension point — hierarchy.</b> The default bean is {@link
 * PartOfOrganizationScopeExpander}. Return {@link #DIRECT_ONLY} from the {@code
 * organizationScopeExpander} bean to scope users to their own organizations only, or implement this
 * interface for another hierarchy model.
 */
@FunctionalInterface
public interface OrganizationScopeExpander {

  /** Returns the organizations unchanged, without any upstream call. */
  OrganizationScopeExpander DIRECT_ONLY = (direct, search) -> Set.copyOf(direct);

  /**
   * Returns every organization the practitioner may see.
   *
   * @param directOrganizationIds bare ids of the practitioner's own organizations
   * @param search the upstream search seam for this request
   * @return the expanded bare ids, always including {@code directOrganizationIds}
   * @throws GatewayFhirSearch.UpstreamException if the scope cannot be fully resolved
   */
  Set<String> expand(Set<String> directOrganizationIds, GatewayFhirSearch search);
}
