package dev.ohs.player.fhir;

import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

/** Minimal JSON representation of a FHIR Reference. */
@NullUnmarked
@Getter
@Setter
public class FhirReference {
  private @Nullable String reference;
  private @Nullable String display;
}
