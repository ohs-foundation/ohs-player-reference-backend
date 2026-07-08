package dev.ohs.player.fhir;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

/** Minimal JSON representation of a FHIR CodeableConcept. */
@NullUnmarked
@Getter
@Setter
public class FhirCodeableConcept {
  private List<FhirCoding> coding = new ArrayList<>();

  /** Keeps coding non-null. */
  public void setCoding(@Nullable List<FhirCoding> coding) {
    this.coding = coding != null ? coding : new ArrayList<>();
  }
}
