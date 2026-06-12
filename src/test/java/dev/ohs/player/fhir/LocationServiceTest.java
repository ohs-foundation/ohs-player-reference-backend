package dev.ohs.player.fhir;

import static org.junit.jupiter.api.Assertions.*;

import org.hl7.fhir.r4.model.Location;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocationServiceTest {

  private LocationService service;

  @BeforeEach
  void setUp() {
    service =
        new LocationService(
            ca.uhn.fhir.context.FhirContext.forR4Cached(), "http://fhir.example.com");
  }

  // -------------------------------------------------------------------------
  // buildLocation — basic fields
  // -------------------------------------------------------------------------

  @Test
  void buildLocation_setsNameAndActiveStatus() {
    LocationData data = new LocationData();
    data.setName("Clinic A");

    Location loc = service.buildLocation(data);

    assertEquals("Clinic A", loc.getName());
    assertEquals(Location.LocationStatus.ACTIVE, loc.getStatus());
  }

  @Test
  void buildLocation_withSourceId_addsIdentifier() {
    LocationData data = new LocationData();
    data.setName("Clinic A");
    data.setSourceId("SRC-001");

    Location loc = service.buildLocation(data);

    assertEquals(1, loc.getIdentifier().size());
    assertEquals(
        LocationService.SOURCE_ID_IDENTIFIER_SYSTEM, loc.getIdentifier().get(0).getSystem());
    assertEquals("SRC-001", loc.getIdentifier().get(0).getValue());
  }

  @Test
  void buildLocation_withoutSourceId_noIdentifier() {
    LocationData data = new LocationData();
    data.setName("Clinic A");

    Location loc = service.buildLocation(data);

    assertTrue(loc.getIdentifier().isEmpty());
  }

  // -------------------------------------------------------------------------
  // buildLocation — physicalType
  // -------------------------------------------------------------------------

  @Test
  void buildLocation_withPhysicalTypeCode_setsPhysicalTypeCoding() {
    LocationData data = new LocationData();
    data.setName("Ward 1");
    data.setPhysicalTypeCode("wa");
    data.setPhysicalTypeDisplay("Ward");

    Location loc = service.buildLocation(data);

    assertFalse(loc.getPhysicalType().isEmpty());
    assertEquals(
        "http://terminology.hl7.org/CodeSystem/location-physical-type",
        loc.getPhysicalType().getCodingFirstRep().getSystem());
    assertEquals("wa", loc.getPhysicalType().getCodingFirstRep().getCode());
    assertEquals("Ward", loc.getPhysicalType().getCodingFirstRep().getDisplay());
  }

  @Test
  void buildLocation_withoutPhysicalType_noPhysicalType() {
    LocationData data = new LocationData();
    data.setName("Clinic A");

    Location loc = service.buildLocation(data);

    assertTrue(loc.getPhysicalType().isEmpty());
  }

  // -------------------------------------------------------------------------
  // buildLocation — level / type
  // -------------------------------------------------------------------------

  @Test
  void buildLocation_withLevel_setsTypeCoding() {
    LocationData data = new LocationData();
    data.setName("County");
    data.setLevel("county");

    Location loc = service.buildLocation(data);

    assertEquals(1, loc.getType().size());
    assertEquals(
        "http://ohs.dev/codes/administrative-level",
        loc.getTypeFirstRep().getCodingFirstRep().getSystem());
    assertEquals("county", loc.getTypeFirstRep().getCodingFirstRep().getCode());
  }

  @Test
  void buildLocation_withoutLevel_noType() {
    LocationData data = new LocationData();
    data.setName("Clinic A");

    Location loc = service.buildLocation(data);

    assertTrue(loc.getType().isEmpty());
  }

  // -------------------------------------------------------------------------
  // buildLocation — position
  // -------------------------------------------------------------------------

  @Test
  void buildLocation_withLongitudeAndLatitude_setsPosition() {
    LocationData data = new LocationData();
    data.setName("Clinic A");
    data.setLongitude(36.8219);
    data.setLatitude(-1.2921);

    Location loc = service.buildLocation(data);

    assertNotNull(loc.getPosition());
    assertEquals(36.8219, loc.getPosition().getLongitude().doubleValue(), 0.0001);
    assertEquals(-1.2921, loc.getPosition().getLatitude().doubleValue(), 0.0001);
  }

  @Test
  void buildLocation_withOnlyLongitude_noPosition() {
    LocationData data = new LocationData();
    data.setName("Clinic A");
    data.setLongitude(36.8219);

    Location loc = service.buildLocation(data);

    assertTrue(loc.getPosition().isEmpty());
  }

  // -------------------------------------------------------------------------
  // buildLocation — managingOrganization
  // -------------------------------------------------------------------------

  @Test
  void buildLocation_withManagingOrg_setsManagingOrganization() {
    LocationData data = new LocationData();
    data.setName("Clinic A");
    data.setManagingOrgFhirId("Organization/org-123");

    Location loc = service.buildLocation(data);

    assertEquals("Organization/org-123", loc.getManagingOrganization().getReference());
  }

  @Test
  void buildLocation_withoutManagingOrg_noManagingOrganization() {
    LocationData data = new LocationData();
    data.setName("Clinic A");

    Location loc = service.buildLocation(data);

    assertTrue(loc.getManagingOrganization().isEmpty());
  }

  // -------------------------------------------------------------------------
  // buildLocation — partOf (parent location)
  // -------------------------------------------------------------------------

  @Test
  void buildLocation_withParentFhirId_setsPartOf() {
    LocationData data = new LocationData();
    data.setName("Clinic A");
    data.setParentFhirId("Location/parent-loc");

    Location loc = service.buildLocation(data);

    assertEquals("Location/parent-loc", loc.getPartOf().getReference());
  }

  @Test
  void buildLocation_withoutParent_noPartOf() {
    LocationData data = new LocationData();
    data.setName("Clinic A");

    Location loc = service.buildLocation(data);

    assertTrue(loc.getPartOf().isEmpty());
  }

  // -------------------------------------------------------------------------
  // buildLocation — aliases (materialized paths)
  // -------------------------------------------------------------------------

  @Test
  void buildLocation_withNameAndUuidPaths_setsTwoAliases() {
    LocationData data = new LocationData();
    data.setName("Clinic A");
    data.setNamePath("Country/County/Clinic A");
    data.setUuidPath("uuid-country/uuid-county/uuid-clinic");

    Location loc = service.buildLocation(data);

    assertEquals(2, loc.getAlias().size());
    assertEquals("Country/County/Clinic A", loc.getAlias().get(0).getValue());
    assertEquals("uuid-country/uuid-county/uuid-clinic", loc.getAlias().get(1).getValue());
  }

  @Test
  void buildLocation_withoutPaths_noAliases() {
    LocationData data = new LocationData();
    data.setName("Clinic A");

    Location loc = service.buildLocation(data);

    assertTrue(loc.getAlias().isEmpty());
  }

  // -------------------------------------------------------------------------
  // resolvePhysicalTypeCode
  // -------------------------------------------------------------------------

  @Test
  void resolvePhysicalTypeCode_knownValues_returnCorrectCodes() {
    assertEquals("si", service.resolvePhysicalTypeCode("site"));
    assertEquals("bu", service.resolvePhysicalTypeCode("building"));
    assertEquals("wi", service.resolvePhysicalTypeCode("wing"));
    assertEquals("wa", service.resolvePhysicalTypeCode("ward"));
    assertEquals("lvl", service.resolvePhysicalTypeCode("level"));
    assertEquals("co", service.resolvePhysicalTypeCode("corridor"));
    assertEquals("ro", service.resolvePhysicalTypeCode("room"));
    assertEquals("bd", service.resolvePhysicalTypeCode("bed"));
    assertEquals("ve", service.resolvePhysicalTypeCode("vehicle"));
    assertEquals("ho", service.resolvePhysicalTypeCode("house"));
    assertEquals("ca", service.resolvePhysicalTypeCode("cabinet"));
    assertEquals("rd", service.resolvePhysicalTypeCode("road"));
    assertEquals("area", service.resolvePhysicalTypeCode("area"));
    assertEquals("jdn", service.resolvePhysicalTypeCode("jurisdiction"));
  }

  @Test
  void resolvePhysicalTypeCode_caseInsensitive() {
    assertEquals("bu", service.resolvePhysicalTypeCode("Building"));
    assertEquals("lvl", service.resolvePhysicalTypeCode("LEVEL"));
    assertEquals("area", service.resolvePhysicalTypeCode("Area"));
  }

  @Test
  void resolvePhysicalTypeCode_unknownNonEmpty_returnsOther() {
    assertEquals("other", service.resolvePhysicalTypeCode("laboratory"));
    assertEquals("other", service.resolvePhysicalTypeCode("unknown-type"));
  }

  @Test
  void resolvePhysicalTypeCode_nullOrBlank_returnsNull() {
    assertNull(service.resolvePhysicalTypeCode(null));
    assertNull(service.resolvePhysicalTypeCode(""));
    assertNull(service.resolvePhysicalTypeCode("   "));
  }

  // -------------------------------------------------------------------------
  // capitalizeFirst
  // -------------------------------------------------------------------------

  @Test
  void capitalizeFirst_lowercaseInput_capitalizesFirstLetter() {
    assertEquals("Building", service.capitalizeFirst("building"));
    assertEquals("Ward", service.capitalizeFirst("ward"));
  }

  @Test
  void capitalizeFirst_alreadyCapitalized_unchanged() {
    assertEquals("Building", service.capitalizeFirst("Building"));
  }

  @Test
  void capitalizeFirst_emptyString_returnsEmpty() {
    assertEquals("", service.capitalizeFirst(""));
  }
}
