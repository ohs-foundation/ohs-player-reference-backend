package dev.ohs.player.iam;

public interface IamProviderService {

  /** Creates a user in the IAM provider and returns the generated user ID. */
  String createUser(IamUser user);

  void updateUser(String iamUserId, IamUser user);

  void deleteUser(String iamUserId);

  /** Resets the credential for the given IAM user. */
  void resetPassword(String iamUserId, String password, boolean temporary);
}
