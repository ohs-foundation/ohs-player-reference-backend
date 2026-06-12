package dev.ohs.player.fhir;

import java.util.List;
import lombok.Data;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

@NullUnmarked
@Data
public class PractitionerRoleData {
  private String practitionerFhirId;
  private @Nullable String organizationFhirId;
  private @Nullable List<String> locationFhirIds;
}
