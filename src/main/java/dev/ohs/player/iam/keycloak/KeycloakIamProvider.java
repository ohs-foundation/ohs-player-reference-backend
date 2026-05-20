package dev.ohs.player.iam.keycloak;

import dev.ohs.player.iam.IamProviderService;
import dev.ohs.player.iam.IamUser;
import jakarta.ws.rs.core.Response;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KeycloakIamProvider implements IamProviderService {

  private static final Logger logger = LoggerFactory.getLogger(KeycloakIamProvider.class);

  private final Keycloak keycloak;
  private final String realm;

  public KeycloakIamProvider(String serverUrl, String realm, String clientId, String clientSecret) {
    if (serverUrl == null || serverUrl.isBlank())
      throw new IllegalArgumentException("IAM server URL (from TOKEN_ISSUER) cannot be blank");
    if (realm == null || realm.isBlank())
      throw new IllegalArgumentException("IAM realm (from TOKEN_ISSUER) cannot be blank");
    if (clientId == null || clientId.isBlank())
      throw new IllegalArgumentException("IAM_PROVIDER_CLIENT_ID cannot be blank");
    if (clientSecret == null || clientSecret.isBlank())
      throw new IllegalArgumentException("IAM_PROVIDER_CLIENT_SECRET cannot be blank");

    this.realm = realm;
    this.keycloak =
        KeycloakBuilder.builder()
            .serverUrl(serverUrl)
            .realm(realm)
            .clientId(clientId)
            .clientSecret(clientSecret)
            .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
            .build();

    logger.info("KeycloakIamProvider initialized: serverUrl={}, realm={}", serverUrl, realm);
  }

  @Override
  public String createUser(IamUser user) {
    try (Response response = keycloak.realm(realm).users().create(toUserRepresentation(user))) {
      if (response.getStatus() != Response.Status.CREATED.getStatusCode()) {
        String errorBody = response.readEntity(String.class);
        logger.info(errorBody);
        throw new RuntimeException(
            "IAM provider returned status "
                + response.getStatus()
                + " creating user: "
                + user.getUsername());
      }
      String location = response.getHeaderString("Location");
      String userId = location.substring(location.lastIndexOf('/') + 1);
      logger.info("Created IAM user: username={}, id={}", user.getUsername(), userId);
      return userId;
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new RuntimeException("Failed to create user in IAM provider: " + user.getUsername(), e);
    }
  }

  @Override
  public void updateUser(String iamUserId, IamUser user) {
    try {
      keycloak.realm(realm).users().get(iamUserId).update(toUserRepresentation(user));
      logger.info("Updated IAM user: id={}", iamUserId);
    } catch (Exception e) {
      throw new RuntimeException("Failed to update user in IAM provider: " + iamUserId, e);
    }
  }

  @Override
  public void deleteUser(String iamUserId) {
    try {
      keycloak.realm(realm).users().get(iamUserId).remove();
      logger.info("Deleted IAM user: id={}", iamUserId);
    } catch (Exception e) {
      throw new RuntimeException("Failed to delete user from IAM provider: " + iamUserId, e);
    }
  }

  @Override
  public void resetPassword(String iamUserId, String password, boolean temporary) {
    try {
      CredentialRepresentation cred = new CredentialRepresentation();
      cred.setType(CredentialRepresentation.PASSWORD);
      cred.setValue(password);
      cred.setTemporary(temporary);
      keycloak.realm(realm).users().get(iamUserId).resetPassword(cred);
      logger.info("Reset password for IAM user: id={}", iamUserId);
    } catch (Exception e) {
      throw new RuntimeException("Failed to reset password for IAM user: " + iamUserId, e);
    }
  }

  private UserRepresentation toUserRepresentation(IamUser user) {
    UserRepresentation rep = new UserRepresentation();
    rep.setUsername(user.getUsername());
    rep.setFirstName(user.getFirstName());
    rep.setLastName(user.getLastName());
    rep.setEmail(user.getEmail());
    rep.setEnabled(user.isEnabled());
    return rep;
  }
}
