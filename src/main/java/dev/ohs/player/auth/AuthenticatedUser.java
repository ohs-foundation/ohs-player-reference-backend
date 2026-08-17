package dev.ohs.player.auth;

import java.util.Collections;
import java.util.Set;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

@Getter
public final class AuthenticatedUser {

  /**
   * The caller's id in the IAM provider, as reported by {@code
   * IamProviderService.extractUserIdFromToken}. For Keycloak this is the {@code sub} claim, but
   * providers that carry the user id elsewhere resolve it there, so this is not necessarily the
   * token subject.
   *
   * <p>Nullable: a provider may be unable to resolve an id from the token, so callers must handle
   * its absence rather than assume every authenticated caller has one.
   */
  @Nullable private final String iamId;

  private final String preferredUsername;
  private final Set<String> roles;

  public AuthenticatedUser(@Nullable String iamId, String preferredUsername, Set<String> roles) {
    this.iamId = iamId;
    this.preferredUsername = preferredUsername;
    this.roles = Collections.unmodifiableSet(roles);
  }
}
