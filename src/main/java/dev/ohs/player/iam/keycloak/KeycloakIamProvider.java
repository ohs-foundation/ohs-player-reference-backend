package dev.ohs.player.iam.keycloak;

import dev.ohs.player.iam.AvailableRolesResponse;
import dev.ohs.player.iam.IamGroup;
import dev.ohs.player.iam.IamGroupRepresentation;
import dev.ohs.player.iam.IamProviderException;
import dev.ohs.player.iam.IamProviderService;
import dev.ohs.player.iam.IamRole;
import dev.ohs.player.iam.IamUser;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.GroupResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.MappingsRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KeycloakIamProvider implements IamProviderService {

  private static final Logger logger = LoggerFactory.getLogger(KeycloakIamProvider.class);

  private final Keycloak keycloak;
  private final String realm;
  private final String clientId;

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
    this.clientId = clientId;
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
        throw new IamProviderException(
            response.getStatus(),
            "IAM provider returned status "
                + response.getStatus()
                + " creating user: "
                + user.getUsername());
      }
      String userId = extractIdFromLocation(response);
      logger.info("Created IAM user: username={}, id={}", user.getUsername(), userId);
      return userId;
    } catch (IamProviderException e) {
      throw e;
    } catch (Exception e) {
      throw wrap("Failed to create user in IAM provider: " + user.getUsername(), e);
    }
  }

  @Override
  public void updateUser(String iamUserId, IamUser user) {
    try {
      keycloak.realm(realm).users().get(iamUserId).update(toUserRepresentation(user));
      logger.info("Updated IAM user: id={}", iamUserId);
    } catch (Exception e) {
      throw wrap("Failed to update user in IAM provider: " + iamUserId, e);
    }
  }

  @Override
  public void deleteUser(String iamUserId) {
    try {
      keycloak.realm(realm).users().get(iamUserId).remove();
      logger.info("Deleted IAM user: id={}", iamUserId);
    } catch (Exception e) {
      throw wrap("Failed to delete user from IAM provider: " + iamUserId, e);
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
      throw wrap("Failed to reset password for IAM user: " + iamUserId, e);
    }
  }

  // --- Group management ---

  @Override
  public IamGroupRepresentation createGroup(IamGroup group) {
    // Resolve all roles before creating the group so no group is persisted when a role is invalid
    ResolvedGroupRoles resolved = resolveGroupRoles(group);

    GroupRepresentation rep = new GroupRepresentation();
    rep.setName(group.getName());
    try (Response response = keycloak.realm(realm).groups().add(rep)) {
      if (response.getStatus() != Response.Status.CREATED.getStatusCode()) {
        String errorBody = response.readEntity(String.class);
        logger.info(errorBody);
        throw new IamProviderException(
            response.getStatus(),
            "IAM provider returned status " + response.getStatus() + " creating group");
      }
      String groupId = extractIdFromLocation(response);
      try {
        assignResolvedGroupRoles(groupId, resolved);
      } catch (Exception roleEx) {
        rollbackGroup(groupId);
        throw roleEx instanceof IamProviderException
            ? (IamProviderException) roleEx
            : wrap("Failed to assign roles to group: " + groupId, roleEx);
      }
      logger.info("Created IAM group: name={}, id={}", group.getName(), groupId);
      return buildGroupRepresentation(groupId);
    } catch (IamProviderException e) {
      throw e;
    } catch (Exception e) {
      throw wrap("Failed to create group in IAM provider: " + group.getName(), e);
    }
  }

  @Override
  public IamGroupRepresentation getGroup(String groupId) {
    try {
      return buildGroupRepresentation(groupId);
    } catch (Exception e) {
      throw wrap("Failed to get group from IAM provider: " + groupId, e);
    }
  }

  @Override
  public List<IamGroupRepresentation> listGroups() {
    try {
      List<GroupRepresentation> groups = keycloak.realm(realm).groups().groups();
      return groups.stream().map(this::toBasicGroupRepresentation).collect(Collectors.toList());
    } catch (Exception e) {
      throw wrap("Failed to list groups from IAM provider", e);
    }
  }

  @Override
  public void updateGroup(String groupId, IamGroup group) {
    // Resolve all roles before touching the group so nothing is modified when a role is invalid
    ResolvedGroupRoles resolved = resolveGroupRoles(group);

    try {
      GroupResource groupResource = keycloak.realm(realm).groups().group(groupId);

      GroupRepresentation rep = groupResource.toRepresentation();
      rep.setName(group.getName());
      groupResource.update(rep);

      replaceResolvedGroupRoles(groupId, resolved);
      logger.info("Updated IAM group: id={}", groupId);
    } catch (Exception e) {
      throw wrap("Failed to update group in IAM provider: " + groupId, e);
    }
  }

  @Override
  public void deleteGroup(String groupId) {
    try {
      keycloak.realm(realm).groups().group(groupId).remove();
      logger.info("Deleted IAM group: id={}", groupId);
    } catch (Exception e) {
      throw wrap("Failed to delete group from IAM provider: " + groupId, e);
    }
  }

  // --- Group membership ---

  @Override
  public void addUserToGroup(String iamUserId, String groupId) {
    try {
      keycloak.realm(realm).users().get(iamUserId).joinGroup(groupId);
      logger.info("Added user {} to group {}", iamUserId, groupId);
    } catch (Exception e) {
      throw wrap("Failed to add user " + iamUserId + " to group " + groupId, e);
    }
  }

  @Override
  public void removeUserFromGroup(String iamUserId, String groupId) {
    try {
      keycloak.realm(realm).users().get(iamUserId).leaveGroup(groupId);
      logger.info("Removed user {} from group {}", iamUserId, groupId);
    } catch (Exception e) {
      throw wrap("Failed to remove user " + iamUserId + " from group " + groupId, e);
    }
  }

  @Override
  public List<IamGroupRepresentation> getUserGroups(String iamUserId) {
    try {
      List<GroupRepresentation> groups = keycloak.realm(realm).users().get(iamUserId).groups();
      return groups.stream().map(this::toBasicGroupRepresentation).collect(Collectors.toList());
    } catch (Exception e) {
      throw wrap("Failed to get groups for IAM user: " + iamUserId, e);
    }
  }

  // --- Role discovery ---

  @Override
  public AvailableRolesResponse listAvailableRoles() {
    try {
      AvailableRolesResponse result = new AvailableRolesResponse();

      List<RoleRepresentation> realmRoles = keycloak.realm(realm).roles().list();
      result.setRealmRoles(realmRoles.stream().map(this::toIamRole).collect(Collectors.toList()));

      List<ClientRepresentation> matches = keycloak.realm(realm).clients().findByClientId(clientId);
      if (matches == null || matches.isEmpty()) {
        throw new IamProviderException(
            404, "Configured client not found in IAM provider: " + clientId);
      }
      ClientRepresentation client = matches.get(0);
      List<RoleRepresentation> clientRoles =
          keycloak.realm(realm).clients().get(client.getId()).roles().list();

      AvailableRolesResponse.ClientRoles cr = new AvailableRolesResponse.ClientRoles();
      cr.setClientId(client.getClientId());
      cr.setClientName(client.getName());
      cr.setRoles(
          clientRoles != null
              ? clientRoles.stream().map(this::toIamRole).collect(Collectors.toList())
              : Collections.emptyList());
      result.setClients(List.of(cr));

      return result;
    } catch (Exception e) {
      throw wrap("Failed to list available roles from IAM provider", e);
    }
  }

  // --- Token introspection ---

  @Override
  public Set<String> extractRolesFromToken(Map<String, Object> claims) {
    Object realmAccess = claims.get("realm_access");
    if (!(realmAccess instanceof Map)) {
      return Collections.emptySet();
    }
    Map<?, ?> realmAccessMap = (Map<?, ?>) realmAccess;
    Object rolesObj = realmAccessMap.get("roles");
    if (!(rolesObj instanceof List)) {
      return Collections.emptySet();
    }
    List<?> rolesList = (List<?>) rolesObj;
    Set<String> roles = new HashSet<>();
    for (Object role : rolesList) {
      if (role instanceof String) {
        roles.add((String) role);
      }
    }
    return roles;
  }

  /**
   * Keycloak issues the user id as the {@code sub} claim. If a realm is configured to expose the id
   * under a different claim (for example via a protocol mapper), change the claim name read here —
   * callers depend only on {@link IamProviderService#extractUserIdFromToken}, not on {@code sub}.
   */
  @Override
  public @Nullable String extractUserIdFromToken(Map<String, Object> claims) {
    Object sub = claims.get("sub");
    return sub instanceof String ? (String) sub : null;
  }

  // --- Private helpers ---

  /**
   * Wraps an exception as an {@link IamProviderException}, preserving the HTTP status code when the
   * cause is a JAX-RS {@link WebApplicationException}.
   */
  private static IamProviderException wrap(String message, Exception e) {
    if (e instanceof IamProviderException) {
      return (IamProviderException) e;
    }
    if (e instanceof WebApplicationException) {
      return new IamProviderException(
          ((WebApplicationException) e).getResponse().getStatus(), message, e);
    }
    return new IamProviderException(502, message, e);
  }

  private String extractIdFromLocation(Response response) {
    String location = response.getHeaderString("Location");
    return location.substring(location.lastIndexOf('/') + 1);
  }

  /**
   * Resolves all roles referenced in {@code group} into Keycloak {@link RoleRepresentation}
   * objects. Throws {@link IamProviderException} (404) if any role or client does not exist. Must
   * be called before any group state is mutated.
   */
  private ResolvedGroupRoles resolveGroupRoles(IamGroup group) {
    List<RoleRepresentation> realmRoles =
        group.getRealmRoles() != null && !group.getRealmRoles().isEmpty()
            ? resolveRealmRoles(group.getRealmRoles())
            : Collections.emptyList();

    Map<String, List<RoleRepresentation>> clientRolesByUuid = new HashMap<>();
    if (group.getClientRoles() != null) {
      for (Map.Entry<String, List<String>> entry : group.getClientRoles().entrySet()) {
        if (entry.getValue() == null || entry.getValue().isEmpty()) continue;
        String uuid = findClientUuid(entry.getKey());
        clientRolesByUuid.put(uuid, resolveClientRolesByUuid(uuid, entry.getValue()));
      }
    }
    return new ResolvedGroupRoles(realmRoles, clientRolesByUuid);
  }

  private void assignResolvedGroupRoles(String groupId, ResolvedGroupRoles resolved) {
    if (!resolved.realmRoles.isEmpty()) {
      keycloak.realm(realm).groups().group(groupId).roles().realmLevel().add(resolved.realmRoles);
    }
    resolved.clientRolesByUuid.forEach(
        (clientUuid, roles) ->
            keycloak
                .realm(realm)
                .groups()
                .group(groupId)
                .roles()
                .clientLevel(clientUuid)
                .add(roles));
  }

  private void replaceResolvedGroupRoles(String groupId, ResolvedGroupRoles resolved) {
    GroupResource groupResource = keycloak.realm(realm).groups().group(groupId);

    List<RoleRepresentation> currentRealmRoles = groupResource.roles().realmLevel().listAll();
    if (!currentRealmRoles.isEmpty()) {
      groupResource.roles().realmLevel().remove(currentRealmRoles);
    }
    if (!resolved.realmRoles.isEmpty()) {
      groupResource.roles().realmLevel().add(resolved.realmRoles);
    }

    MappingsRepresentation currentMappings = groupResource.roles().getAll();
    if (currentMappings.getClientMappings() != null) {
      currentMappings
          .getClientMappings()
          .forEach(
              (cId, mapping) -> {
                String clientUuid = findClientUuid(cId);
                groupResource.roles().clientLevel(clientUuid).remove(mapping.getMappings());
              });
    }
    resolved.clientRolesByUuid.forEach(
        (clientUuid, roles) -> groupResource.roles().clientLevel(clientUuid).add(roles));
  }

  private void rollbackGroup(String groupId) {
    try {
      keycloak.realm(realm).groups().group(groupId).remove();
      logger.info("Rolled back group creation: id={}", groupId);
    } catch (Exception e) {
      logger.error("Failed to roll back group: id={}; manual cleanup required", groupId, e);
    }
  }

  private IamGroupRepresentation buildGroupRepresentation(String groupId) {
    GroupResource groupResource = keycloak.realm(realm).groups().group(groupId);
    GroupRepresentation groupRep = groupResource.toRepresentation();
    MappingsRepresentation roleMappings = groupResource.roles().getAll();

    IamGroupRepresentation result = new IamGroupRepresentation();
    result.setId(groupRep.getId());
    result.setName(groupRep.getName());
    result.setPath(groupRep.getPath());

    if (roleMappings.getRealmMappings() != null) {
      result.setRealmRoles(
          roleMappings.getRealmMappings().stream()
              .map(RoleRepresentation::getName)
              .collect(Collectors.toList()));
    } else {
      result.setRealmRoles(Collections.emptyList());
    }

    if (roleMappings.getClientMappings() != null) {
      Map<String, List<String>> clientRoles = new HashMap<>();
      roleMappings
          .getClientMappings()
          .forEach(
              (clientId, mapping) ->
                  clientRoles.put(
                      clientId,
                      mapping.getMappings().stream()
                          .map(RoleRepresentation::getName)
                          .collect(Collectors.toList())));
      result.setClientRoles(clientRoles);
    } else {
      result.setClientRoles(Collections.emptyMap());
    }

    return result;
  }

  private IamGroupRepresentation toBasicGroupRepresentation(GroupRepresentation rep) {
    IamGroupRepresentation result = new IamGroupRepresentation();
    result.setId(rep.getId());
    result.setName(rep.getName());
    result.setPath(rep.getPath());
    return result;
  }

  private List<RoleRepresentation> resolveRealmRoles(List<String> names) {
    return names.stream()
        .map(name -> keycloak.realm(realm).roles().get(name).toRepresentation())
        .collect(Collectors.toList());
  }

  private List<RoleRepresentation> resolveClientRolesByUuid(String clientUuid, List<String> names) {
    return names.stream()
        .map(
            name ->
                keycloak
                    .realm(realm)
                    .clients()
                    .get(clientUuid)
                    .roles()
                    .get(name)
                    .toRepresentation())
        .collect(Collectors.toList());
  }

  private String findClientUuid(String clientId) {
    List<ClientRepresentation> clients = keycloak.realm(realm).clients().findByClientId(clientId);
    if (clients == null || clients.isEmpty()) {
      throw new IamProviderException(404, "Client not found in IAM provider: " + clientId);
    }
    return clients.get(0).getId();
  }

  private IamRole toIamRole(RoleRepresentation rep) {
    IamRole role = new IamRole();
    role.setId(rep.getId());
    role.setName(rep.getName());
    role.setDescription(rep.getDescription());
    return role;
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

  private static final class ResolvedGroupRoles {
    final List<RoleRepresentation> realmRoles;

    /** Keyed by Keycloak client UUID (not client ID). */
    final Map<String, List<RoleRepresentation>> clientRolesByUuid;

    ResolvedGroupRoles(
        List<RoleRepresentation> realmRoles,
        Map<String, List<RoleRepresentation>> clientRolesByUuid) {
      this.realmRoles = realmRoles;
      this.clientRolesByUuid = clientRolesByUuid;
    }
  }
}
