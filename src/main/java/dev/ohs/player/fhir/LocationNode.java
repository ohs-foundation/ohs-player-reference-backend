package dev.ohs.player.fhir;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.jspecify.annotations.NullUnmarked;
import org.jspecify.annotations.Nullable;

/**
 * Lean JSON representation of a FHIR Location in the hierarchy response.
 *
 * <p>{@code @Getter}/{@code @Setter} are used rather than {@code @Data} so the recursive node never
 * generates whole-subtree {@code equals}, {@code hashCode}, or {@code toString} methods.
 */
@NullUnmarked
@Getter
@Setter
public class LocationNode {
  private String id;
  private @Nullable String name;
  private @Nullable String status;
  private @Nullable String description;
  private @Nullable FhirReference partOf;
  private @Nullable FhirCodeableConcept physicalType;
  private List<FhirCodeableConcept> type = new ArrayList<>();
  private List<LocationNode> children = new ArrayList<>();
  private boolean hasMoreChildren;

  /** Keeps type non-null. */
  public void setType(@Nullable List<FhirCodeableConcept> type) {
    this.type = type != null ? type : new ArrayList<>();
  }

  /** Keeps children non-null. */
  public void setChildren(@Nullable List<LocationNode> children) {
    this.children = children != null ? children : new ArrayList<>();
  }
}
