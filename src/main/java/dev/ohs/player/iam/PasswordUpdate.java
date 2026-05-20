package dev.ohs.player.iam;

import lombok.Data;

@Data
public class PasswordUpdate {

  private String password;
  private boolean temporary;
}
