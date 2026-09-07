package dev.ohs.player.iam;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public interface IamProviderService {

  /** Creates a user in the IAM provider and returns the generated user ID. */
  String createUser(IamUser user);

  void updateUser(String iamUserId, IamUser user);

  void deleteUser(String iamUserId);

  /** Resets the credential for the given IAM user. */
  void resetPassword(String iamUserId, String password, boolean temporary);

  // --- Group management ---

  IamGroupRepresentation createGroup(IamGroup group);

  IamGroupRepresentation getGroup(String groupId);

  List<IamGroupRepresentation> listGroups();

  void updateGroup(String groupId, IamGroup group);

  void deleteGroup(String groupId);

  // --- Group membership ---

  void addUserToGroup(String iamUserId, String groupId);

  void removeUserFromGroup(String iamUserId, String groupId);

  /** Returns the groups the given IAM user currently belongs to. */
  List<IamGroupRepresentation> getUserGroups(String iamUserId);

  // --- Role discovery ---

  AvailableRolesResponse listAvailableRoles();

  // --- Token introspection ---

  /**
   * Extracts the set of role names from the given JWT claims map. Each IAM implementation is
   * responsible for locating roles within the provider-specific claim structure.
   */
  Set<String> extractRolesFromToken(Map<String, Object> claims);

  /**
   * Extracts the IAM user id of the token subject from the given JWT claims map. Each IAM
   * implementation is responsible for locating the id within the provider-specific claim structure.
   * Returns {@code null} when the claim is absent or not a string.
   *
   * <p>This is deliberately not hardcoded to {@code sub}: providers that carry the user id in a
   * different claim (for example {@code oid}, or a mapper-supplied custom claim) resolve it here,
   * so callers never assume the id equals the token subject.
   */
  @Nullable String extractUserIdFromToken(Map<String, Object> claims);
}
