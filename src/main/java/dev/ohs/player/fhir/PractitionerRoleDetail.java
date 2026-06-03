package dev.ohs.player.fhir;

import java.util.List;
import lombok.Data;
import org.hl7.fhir.r4.model.CareTeam;
import org.hl7.fhir.r4.model.Location;
import org.hl7.fhir.r4.model.Organization;
import org.hl7.fhir.r4.model.PractitionerRole;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

@NullUnmarked
@Data
public class PractitionerRoleDetail {
  private PractitionerRole practitionerRole;
  private @Nullable Organization organization;
  private List<Location> locations;
  private List<CareTeam> careTeams;
}
