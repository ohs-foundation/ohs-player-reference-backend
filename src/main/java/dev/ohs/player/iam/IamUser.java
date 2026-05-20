package dev.ohs.player.iam;

import lombok.Data;

@Data
public class IamUser {

  private String username;
  private String firstName;
  private String lastName;
  private String email;
  private boolean enabled;
  private boolean temporaryPassword;
}
