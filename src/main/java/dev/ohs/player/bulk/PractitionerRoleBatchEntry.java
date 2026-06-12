package dev.ohs.player.bulk;

import dev.ohs.player.fhir.PractitionerRoleData;
import org.jspecify.annotations.NullUnmarked;

/** Holds all per-row context needed to build a FHIR batch bundle entry for a PractitionerRole. */
@NullUnmarked
class PractitionerRoleBatchEntry {

  private final String uuid;
  private final PractitionerRoleData data;
  private final int rowNumber;

  PractitionerRoleBatchEntry(String uuid, PractitionerRoleData data, int rowNumber) {
    this.uuid = uuid;
    this.data = data;
    this.rowNumber = rowNumber;
  }

  String getUuid() {
    return uuid;
  }

  PractitionerRoleData getData() {
    return data;
  }

  int getRowNumber() {
    return rowNumber;
  }
}
