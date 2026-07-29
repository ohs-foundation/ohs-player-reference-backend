package dev.ohs.player.fhir;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.CacheControlDirective;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.IQuery;
import ca.uhn.fhir.rest.gclient.IUntypedQuery;
import org.hl7.fhir.instance.model.api.IBaseBundle;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.Practitioner;
import org.hl7.fhir.r4.model.Resource;
import org.mockito.ArgumentCaptor;

/**
 * Mockito harness for the {@code client.search().byUrl(...).returnBundle(...).cacheControl(...)}
 * chain used by the id lookups, exposing the search URL and cache directive actually sent.
 */
final class SearchClientMocks {

  static final String FHIR_SERVER_URL = "http://fhir.example.com/fhir";

  final FhirContext fhirContext = mock(FhirContext.class);
  final IGenericClient client = mock(IGenericClient.class);
  final IUntypedQuery<IBaseBundle> search;
  final IQuery<IBaseBundle> untypedQuery;
  final IQuery<Bundle> query;

  @SuppressWarnings("unchecked")
  SearchClientMocks(Bundle searchResult) {
    search = mock(IUntypedQuery.class);
    untypedQuery = mock(IQuery.class);
    query = mock(IQuery.class);

    when(fhirContext.newRestfulGenericClient(FHIR_SERVER_URL)).thenReturn(client);
    when(client.search()).thenReturn(search);
    when(search.byUrl(any())).thenReturn(untypedQuery);
    when(untypedQuery.returnBundle(Bundle.class)).thenReturn(query);
    when(query.cacheControl(any())).thenReturn(query);
    when(query.execute()).thenReturn(searchResult);
  }

  /** The search URL passed to {@code byUrl(...)}. */
  String capturedUrl() {
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(search).byUrl(captor.capture());
    return captor.getValue();
  }

  /** The directive passed to {@code cacheControl(...)}. */
  CacheControlDirective capturedCacheControl() {
    ArgumentCaptor<CacheControlDirective> captor =
        ArgumentCaptor.forClass(CacheControlDirective.class);
    verify(query).cacheControl(captor.capture());
    return captor.getValue();
  }

  static Bundle emptyBundle() {
    return new Bundle();
  }

  static Bundle bundleWith(Resource... resources) {
    Bundle bundle = new Bundle();
    for (Resource resource : resources) {
      bundle.addEntry().setResource(resource);
    }
    return bundle;
  }

  static Practitioner practitioner(String id) {
    Practitioner practitioner = new Practitioner();
    practitioner.setId("Practitioner/" + id);
    return practitioner;
  }

  static Organization organization(String id) {
    Organization organization = new Organization();
    organization.setId("Organization/" + id);
    return organization;
  }

  static Location location(String id) {
    Location location = new Location();
    location.setId("Location/" + id);
    return location;
  }
}
