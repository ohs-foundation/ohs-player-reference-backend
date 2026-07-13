package dev.ohs.player.fhir;

import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

/** Minimal JSON representation of a FHIR Coding. */
@NullUnmarked
@Getter
@Setter
public class FhirCoding {
  private @Nullable String system;
  private @Nullable String code;
  private @Nullable String display;
}
