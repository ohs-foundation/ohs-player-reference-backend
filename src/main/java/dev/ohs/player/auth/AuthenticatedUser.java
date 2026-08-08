package dev.ohs.player.auth;

import java.util.Collections;
import java.util.Set;
import lombok.Getter;

@Getter
public final class AuthenticatedUser {

  /**
   * The caller's id in the IAM provider, as reported by {@code
   * IamProviderService.extractUserIdFromToken}. For Keycloak this is the {@code sub} claim, but
   * providers that carry the user id elsewhere resolve it there, so this is not necessarily the
   * token subject.
   */
  private final String iamId;

  private final String preferredUsername;
  private final Set<String> roles;

  public AuthenticatedUser(String iamId, String preferredUsername, Set<String> roles) {
    this.iamId = iamId;
    this.preferredUsername = preferredUsername;
    this.roles = Collections.unmodifiableSet(roles);
  }
}
