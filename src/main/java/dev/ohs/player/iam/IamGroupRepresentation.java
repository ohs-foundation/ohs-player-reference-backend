package dev.ohs.player.iam;

import java.util.List;
import java.util.Map;
import lombok.Data;
import org.jspecify.annotations.NullUnmarked;

@NullUnmarked
@Data
public class IamGroupRepresentation {

  private String id;
  private String name;
  private String path;
  private List<String> realmRoles;
  private Map<String, List<String>> clientRoles;
}
