package dev.ohs.player.fhir;

import static dev.ohs.player.fhir.SearchClientMocks.FHIR_SERVER_URL;
import static dev.ohs.player.fhir.SearchClientMocks.bundleWith;
import static dev.ohs.player.fhir.SearchClientMocks.emptyBundle;
import static dev.ohs.player.fhir.SearchClientMocks.practitioner;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FhirIdLookupTest {

  @Test
  void findFirstId_sendsNoCacheDirective() {
    SearchClientMocks mocks = new SearchClientMocks(bundleWith(practitioner("123")));

    FhirIdLookup.findFirstId(mocks.client, FHIR_SERVER_URL, "Practitioner", "identifier", "sys|1");

    assertTrue(
        mocks.capturedCacheControl().isNoCache(),
        "lookups must set Cache-Control: no-cache so the server does not replay a cached,"
            + " pre-create search result");
  }

  @Test
  void findFirstId_doesNotSetNoStore() {
    SearchClientMocks mocks = new SearchClientMocks(bundleWith(practitioner("123")));

    FhirIdLookup.findFirstId(mocks.client, FHIR_SERVER_URL, "Practitioner", "identifier", "sys|1");

    assertFalse(mocks.capturedCacheControl().isNoStore());
  }

  @Test
  void findFirstId_encodesTokenValueAndRequestsIdOnly() {
    SearchClientMocks mocks = new SearchClientMocks(bundleWith(practitioner("123")));

    FhirIdLookup.findFirstId(
        mocks.client,
        FHIR_SERVER_URL,
        "Practitioner",
        "identifier",
        "http://ohs.dev/identifiers/source-id|1");

    assertEquals(
        "http://fhir.example.com/fhir/Practitioner?identifier="
            + "http%3A%2F%2Fohs.dev%2Fidentifiers%2Fsource-id%7C1&_elements=id",
        mocks.capturedUrl());
  }

  @Test
  void findFirstId_appendsSingleSlashWhenBaseUrlHasTrailingSlash() {
    SearchClientMocks mocks = new SearchClientMocks(bundleWith(practitioner("123")));

    FhirIdLookup.findFirstId(
        mocks.client, FHIR_SERVER_URL + "/", "Organization", "name", "Kisumu County");

    assertEquals(
        "http://fhir.example.com/fhir/Organization?name=Kisumu+County&_elements=id",
        mocks.capturedUrl());
  }

  @Test
  void findFirstId_returnsIdPartOfFirstEntry() {
    SearchClientMocks mocks =
        new SearchClientMocks(bundleWith(practitioner("1097"), practitioner("1098")));

    String id =
        FhirIdLookup.findFirstId(
            mocks.client, FHIR_SERVER_URL, "Practitioner", "identifier", "sys|1");

    assertEquals("1097", id);
  }

  @Test
  void findFirstId_returnsNullWhenNoMatch() {
    SearchClientMocks mocks = new SearchClientMocks(emptyBundle());

    assertNull(
        FhirIdLookup.findFirstId(
            mocks.client, FHIR_SERVER_URL, "Practitioner", "identifier", "sys|missing"));
  }
}
