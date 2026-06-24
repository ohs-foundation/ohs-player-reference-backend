package dev.ohs.player.fhir;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NullUnmarked;

/** Metadata describing the emitted Location hierarchy. */
@NullUnmarked
@Getter
@Setter
public class LocationHierarchyMeta {
  private int nodeCount;
  private int depth;
  private boolean truncated;
  private Instant builtAt;
}
