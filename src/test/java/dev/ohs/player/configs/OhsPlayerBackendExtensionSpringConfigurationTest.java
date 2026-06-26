package dev.ohs.player.configs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

import dev.ohs.player.endpoints.LocationHierarchyServlet;
import dev.ohs.player.fhir.LocationHierarchyService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.ServletRegistrationBean;

class OhsPlayerBackendExtensionSpringConfigurationTest {

  @Test
  void locationHierarchyServlet_RegistersExpectedRoute() {
    OhsPlayerBackendExtensionSpringConfiguration configuration =
        new OhsPlayerBackendExtensionSpringConfiguration();
    configuration.locationHierarchyService = mock(LocationHierarchyService.class);

    ServletRegistrationBean<LocationHierarchyServlet> registration =
        configuration.locationHierarchyServlet();

    assertInstanceOf(LocationHierarchyServlet.class, registration.getServlet());
    assertEquals(1, registration.getUrlMappings().size());
    assertEquals("/api/location-hierarchy/*", registration.getUrlMappings().iterator().next());
  }
}
