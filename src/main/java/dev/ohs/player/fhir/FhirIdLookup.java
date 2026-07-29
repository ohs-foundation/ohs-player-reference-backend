package dev.ohs.player.fhir;

import ca.uhn.fhir.rest.api.CacheControlDirective;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.hl7.fhir.r4.model.Bundle;
import org.jspecify.annotations.Nullable;

/**
 * Resolves a FHIR resource's server-assigned id from a single search parameter.
 *
 * <p>Lookups are sent with {@code Cache-Control: no-cache} so the FHIR server re-runs the search
 * instead of replaying a cached result set. HAPI JPA reuses search results for {@code
 * reuse_cached_search_results_millis} (60 seconds by default), which makes a resource created just
 * after an identical unsuccessful search appear to be missing — a resource created by one bulk
 * import would not be resolvable by the next import running within that window.
 */
final class FhirIdLookup {

  private FhirIdLookup() {}

  /**
   * Returns the id of the first resource matching {@code searchParam=value}, or {@code null} when
   * the search matches nothing.
   *
   * @param client client for the FHIR server being searched
   * @param fhirServerUrl base URL of the FHIR server, with or without a trailing slash
   * @param resourceType FHIR resource type to search, e.g. {@code Practitioner}
   * @param searchParam FHIR search parameter name, e.g. {@code identifier}
   * @param value search parameter value; encoded by this method, so token values are passed as
   *     {@code system|code}
   */
  static @Nullable String findFirstId(
      IGenericClient client,
      String fhirServerUrl,
      String resourceType,
      String searchParam,
      String value) {

    String base = fhirServerUrl.endsWith("/") ? fhirServerUrl : fhirServerUrl + "/";
    String url =
        base
            + resourceType
            + "?"
            + searchParam
            + "="
            + URLEncoder.encode(value, StandardCharsets.UTF_8)
            + "&_elements=id";

    Bundle result =
        client
            .search()
            .byUrl(url)
            .returnBundle(Bundle.class)
            .cacheControl(new CacheControlDirective().setNoCache(true))
            .execute();

    if (result.getEntry().isEmpty()) return null;
    return result.getEntry().get(0).getResource().getIdElement().getIdPart();
  }
}
