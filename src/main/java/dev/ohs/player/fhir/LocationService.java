package dev.ohs.player.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Reference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocationService {

  private static final Logger logger = LoggerFactory.getLogger(LocationService.class);

  public static final String SOURCE_ID_IDENTIFIER_SYSTEM = "http://ohs.dev/identifiers/source-id";
  private static final String ADMINISTRATIVE_LEVEL_SYSTEM =
      "http://ohs.dev/codes/administrative-level";
  private static final String PHYSICAL_TYPE_SYSTEM =
      "http://terminology.hl7.org/CodeSystem/location-physical-type";

  private final FhirContext fhirContext;
  private final String fhirServerUrl;

  public LocationService(FhirContext fhirContext, String fhirServerUrl) {
    if (fhirContext == null) throw new IllegalArgumentException("FhirContext cannot be null");
    if (fhirServerUrl == null || fhirServerUrl.isBlank())
      throw new IllegalArgumentException("PROXY_TO is required");
    this.fhirContext = fhirContext;
    this.fhirServerUrl = fhirServerUrl;
  }

  public Bundle executeBundle(Bundle bundle) {
    IGenericClient client = fhirContext.newRestfulGenericClient(fhirServerUrl);
    return client.transaction().withBundle(bundle).execute();
  }

  public Location getLocation(String locationId) {
    IGenericClient client = fhirContext.newRestfulGenericClient(fhirServerUrl);
    return client.read().resource(Location.class).withId(locationId).execute();
  }

  public @Nullable String findLocationIdByIdentifier(String system, String value) {
    IGenericClient client = fhirContext.newRestfulGenericClient(fhirServerUrl);
    String encoded = URLEncoder.encode(system + "|" + value, StandardCharsets.UTF_8);
    String base = fhirServerUrl.endsWith("/") ? fhirServerUrl : fhirServerUrl + "/";
    Bundle result =
        client.fetchResourceFromUrl(
            Bundle.class, base + "Location?identifier=" + encoded + "&_elements=id");
    if (result.getEntry().isEmpty()) return null;
    return result.getEntry().get(0).getResource().getIdElement().getIdPart();
  }

  public @Nullable String findLocationIdByName(String name) {
    IGenericClient client = fhirContext.newRestfulGenericClient(fhirServerUrl);
    String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);
    String base = fhirServerUrl.endsWith("/") ? fhirServerUrl : fhirServerUrl + "/";
    Bundle result =
        client.fetchResourceFromUrl(
            Bundle.class, base + "Location?name=" + encoded + "&_elements=id");
    if (result.getEntry().isEmpty()) return null;
    return result.getEntry().get(0).getResource().getIdElement().getIdPart();
  }

  public Location buildLocation(LocationData data) {
    Location location = new Location();
    location.setStatus(Location.LocationStatus.ACTIVE);
    location.setName(data.getName());

    if (data.getSourceId() != null) {
      Identifier identifier = new Identifier();
      identifier.setSystem(SOURCE_ID_IDENTIFIER_SYSTEM);
      identifier.setValue(data.getSourceId());
      location.addIdentifier(identifier);
    }

    if (data.getPhysicalTypeCode() != null) {
      CodeableConcept physicalType = new CodeableConcept();
      Coding coding = new Coding();
      coding.setSystem(PHYSICAL_TYPE_SYSTEM);
      coding.setCode(data.getPhysicalTypeCode());
      if (data.getPhysicalTypeDisplay() != null) {
        coding.setDisplay(data.getPhysicalTypeDisplay());
      }
      physicalType.addCoding(coding);
      location.setPhysicalType(physicalType);
    }

    if (data.getLevel() != null) {
      CodeableConcept type = new CodeableConcept();
      Coding coding = new Coding();
      coding.setSystem(ADMINISTRATIVE_LEVEL_SYSTEM);
      coding.setCode(data.getLevel());
      type.addCoding(coding);
      location.addType(type);
    }

    if (data.getLongitude() != null && data.getLatitude() != null) {
      Location.LocationPositionComponent position = new Location.LocationPositionComponent();
      position.setLongitude(BigDecimal.valueOf(data.getLongitude()));
      position.setLatitude(BigDecimal.valueOf(data.getLatitude()));
      location.setPosition(position);
    }

    if (data.getManagingOrgFhirId() != null) {
      location.setManagingOrganization(new Reference(data.getManagingOrgFhirId()));
    }

    if (data.getParentFhirId() != null) {
      location.setPartOf(new Reference(data.getParentFhirId()));
    }

    if (data.getNamePath() != null) {
      location.addAlias(data.getNamePath());
    }
    if (data.getUuidPath() != null) {
      location.addAlias(data.getUuidPath());
    }

    return location;
  }

  /**
   * Maps a CSV physical_type display value to its FHIR location-physical-type code. Returns {@code
   * "other"} for non-empty values not in the valueset. Returns {@code null} for null or blank
   * input.
   */
  public @Nullable String resolvePhysicalTypeCode(@Nullable String input) {
    if (input == null || input.isBlank()) return null;
    switch (input.toLowerCase().trim()) {
      case "site":
        return "si";
      case "building":
        return "bu";
      case "wing":
        return "wi";
      case "ward":
        return "wa";
      case "level":
        return "lvl";
      case "corridor":
        return "co";
      case "room":
        return "ro";
      case "bed":
        return "bd";
      case "vehicle":
        return "ve";
      case "house":
        return "ho";
      case "cabinet":
        return "ca";
      case "road":
        return "rd";
      case "area":
        return "area";
      case "jurisdiction":
        return "jdn";
      default:
        return "other";
    }
  }

  /** Capitalizes the first character of a non-empty string. */
  public String capitalizeFirst(String value) {
    if (value.isEmpty()) return value;
    return Character.toUpperCase(value.charAt(0)) + value.substring(1);
  }
}
