package dev.ohs.player.fhir;

import lombok.Data;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

@NullUnmarked
@Data
public class LocationData {
  private String name;
  private @Nullable String sourceId;
  private @Nullable String physicalTypeCode;
  private @Nullable String physicalTypeDisplay;
  private @Nullable String level;
  private @Nullable Double longitude;
  private @Nullable Double latitude;
  private @Nullable String managingOrgFhirId;
  private @Nullable String parentFhirId;
  private @Nullable String namePath;
  private @Nullable String uuidPath;
}
