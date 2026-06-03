package dev.ohs.player.fhir;

import java.util.List;
import lombok.Data;
import org.hl7.fhir.r4.model.Practitioner;
import org.jspecify.annotations.NullUnmarked;

@NullUnmarked
@Data
public class PractitionerDetail {
  private Practitioner practitioner;
  private List<PractitionerRoleDetail> practitionerRoles;
}
