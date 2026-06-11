package dev.ohs.player.bulk;

import dev.ohs.player.fhir.OrgData;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

/** Holds all per-row context needed to build a FHIR batch bundle entry. */
@NullUnmarked
class OrgBatchEntry {

  private final String uuid;
  private final OrgData orgData;
  private final @Nullable String fhirId;
  private final int rowNumber;

  OrgBatchEntry(String uuid, OrgData orgData, @Nullable String fhirId, int rowNumber) {
    this.uuid = uuid;
    this.orgData = orgData;
    this.fhirId = fhirId;
    this.rowNumber = rowNumber;
  }

  String getUuid() {
    return uuid;
  }

  OrgData getOrgData() {
    return orgData;
  }

  @Nullable String getFhirId() {
    return fhirId;
  }

  int getRowNumber() {
    return rowNumber;
  }
}
