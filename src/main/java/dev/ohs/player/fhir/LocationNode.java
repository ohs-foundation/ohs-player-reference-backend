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
  private @Nullable String partOf;
  private List<LocationNode> children = new ArrayList<>();
  private boolean hasMoreChildren;

  /**
   * Coalesces a null assignment to an empty list so {@code children} is never serialized as null.
   */
  public void setChildren(@Nullable List<LocationNode> children) {
    this.children = children != null ? children : new ArrayList<>();
  }
}
