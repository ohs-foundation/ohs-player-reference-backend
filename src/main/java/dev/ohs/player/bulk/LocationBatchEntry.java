package dev.ohs.player.bulk;

import dev.ohs.player.fhir.LocationData;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

/** Holds all per-row context needed to build a FHIR batch bundle entry for a Location. */
@NullUnmarked
class LocationBatchEntry {

  private final String uuid;
  private final LocationData locationData;
  private final @Nullable String fhirId;
  private final int rowNumber;

  LocationBatchEntry(
      String uuid, LocationData locationData, @Nullable String fhirId, int rowNumber) {
    this.uuid = uuid;
    this.locationData = locationData;
    this.fhirId = fhirId;
    this.rowNumber = rowNumber;
  }

  String getUuid() {
    return uuid;
  }

  LocationData getLocationData() {
    return locationData;
  }

  @Nullable String getFhirId() {
    return fhirId;
  }

  int getRowNumber() {
    return rowNumber;
  }
}
