package dev.ohs.player.auth;

import java.util.Collections;
import java.util.Set;
import lombok.Getter;

@Getter
public final class AuthenticatedUser {

  private final String sub;
  private final String preferredUsername;
  private final Set<String> roles;

  public AuthenticatedUser(String sub, String preferredUsername, Set<String> roles) {
    this.sub = sub;
    this.preferredUsername = preferredUsername;
    this.roles = Collections.unmodifiableSet(roles);
  }
}
