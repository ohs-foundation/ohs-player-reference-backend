package dev.ohs.player.iam;

import lombok.Data;
import org.jspecify.annotations.NullUnmarked;

@NullUnmarked
@Data
public class IamRole {

  private String id;
  private String name;
  private String description;
}
