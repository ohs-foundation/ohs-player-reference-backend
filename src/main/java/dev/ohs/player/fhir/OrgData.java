package dev.ohs.player.fhir;

import lombok.Data;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

@NullUnmarked
@Data
public class OrgData {
  private String name;
  private @Nullable String sourceId;
  private boolean team;
  private @Nullable String phone;
  private @Nullable String email;
  private @Nullable String physicalAddress;
  private @Nullable String postalAddress;
  private @Nullable String parentFhirId;
}
