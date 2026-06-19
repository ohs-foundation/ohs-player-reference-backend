package dev.ohs.player.iam;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
}
