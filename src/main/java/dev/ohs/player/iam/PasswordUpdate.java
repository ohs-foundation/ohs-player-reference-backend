package dev.ohs.player.iam;

import lombok.Data;
import org.jspecify.annotations.NullUnmarked;

@NullUnmarked
@Data
public class PasswordUpdate {

  private String password;
  private boolean temporary;
}
