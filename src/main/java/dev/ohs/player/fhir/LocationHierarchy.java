package dev.ohs.player.fhir;

import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NullUnmarked;

/** Response DTO for a Location hierarchy rooted at a requested FHIR Location. */
@NullUnmarked
@Getter
@Setter
public class LocationHierarchy {
  private LocationNode root;
  private LocationHierarchyMeta meta;
}
