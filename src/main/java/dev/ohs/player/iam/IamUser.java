package dev.ohs.player.iam;

import java.util.List;
import lombok.Data;

@Data
public class IamUser {

  private String username;
  private String firstName;
  private String lastName;
  private String email;
  private boolean enabled;
  private boolean temporaryPassword;

  /**
   * Desired group memberships. Null = leave unchanged (on update). Empty list = remove from all
   * groups. Non-empty = set exact membership.
   */
  private List<String> groupIds;
}
