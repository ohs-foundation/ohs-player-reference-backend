package dev.ohs.player.iam;

import java.util.List;
import lombok.Data;

@Data
public class AvailableRolesResponse {

  private List<IamRole> realmRoles;
  private List<ClientRoles> clients;

  @Data
  public static class ClientRoles {
    private String clientId;
    private String clientName;
    private List<IamRole> roles;
  }
}
