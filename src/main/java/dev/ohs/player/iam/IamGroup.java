package dev.ohs.player.iam;

import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class IamGroup {

  private String name;
  private List<String> realmRoles;
  private Map<String, List<String>> clientRoles;
}
