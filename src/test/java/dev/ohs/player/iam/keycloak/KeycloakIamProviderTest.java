package dev.ohs.player.iam.keycloak;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.ohs.player.iam.AvailableRolesResponse;
import dev.ohs.player.iam.IamGroup;
import dev.ohs.player.iam.IamGroupRepresentation;
import dev.ohs.player.iam.IamProviderException;
import dev.ohs.player.iam.IamUser;
import jakarta.ws.rs.WebApplicationException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.ClientResource;
import org.keycloak.admin.client.resource.ClientsResource;
import org.keycloak.admin.client.resource.GroupResource;
import org.keycloak.admin.client.resource.GroupsResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleMappingResource;
import org.keycloak.admin.client.resource.RoleScopeResource;
import org.keycloak.admin.client.resource.RolesResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.MappingsRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

class KeycloakIamProviderTest {

  private static final String SERVER_URL = "http://keycloak";
  private static final String REALM = "test-realm";
  private static final String CLIENT_ID = "client-id";
  private static final String CLIENT_SECRET = "client-secret";

  // -------------------------------------------------------------------------
  // User management — requires mocked Keycloak admin client
  // -------------------------------------------------------------------------

  @Nested
  @ExtendWith(MockitoExtension.class)
  class UserManagement {

    @Mock private Keycloak mockKeycloak;
    @Mock private RealmResource mockRealm;
    @Mock private UsersResource mockUsersResource;
    @Mock private UserResource mockUserResource;
    @Mock private jakarta.ws.rs.core.Response mockResponse;

    private KeycloakIamProvider provider;

    private static final String IAM_USER_ID = "keycloak-uuid-123";

    @BeforeEach
    void setUp() throws Exception {
      provider = new KeycloakIamProvider(SERVER_URL, REALM, CLIENT_ID, CLIENT_SECRET);
      Field keycloakField = KeycloakIamProvider.class.getDeclaredField("keycloak");
      keycloakField.setAccessible(true);
      keycloakField.set(provider, mockKeycloak);

      lenient().when(mockKeycloak.realm(REALM)).thenReturn(mockRealm);
      lenient().when(mockRealm.users()).thenReturn(mockUsersResource);
      lenient().when(mockUsersResource.get(IAM_USER_ID)).thenReturn(mockUserResource);
    }

    // createUser

    @Test
    void createUser_HappyPath_ReturnsExtractedUserId() {
      when(mockUsersResource.create(any())).thenReturn(mockResponse);
      when(mockResponse.getStatus()).thenReturn(201);
      when(mockResponse.getHeaderString("Location"))
          .thenReturn(SERVER_URL + "/admin/realms/" + REALM + "/users/" + IAM_USER_ID);

      String result = provider.createUser(iamUser("alice"));

      assertEquals(IAM_USER_ID, result);
    }

    @Test
    void createUser_IamReturnsNon201_ThrowsIamProviderExceptionWithUpstreamStatus() {
      when(mockUsersResource.create(any())).thenReturn(mockResponse);
      when(mockResponse.getStatus()).thenReturn(409);
      when(mockResponse.readEntity(String.class)).thenReturn("Conflict");

      IamProviderException ex =
          assertThrows(IamProviderException.class, () -> provider.createUser(iamUser("alice")));
      assertEquals(409, ex.getStatusCode());
    }

    @Test
    void createUser_IamThrows_WrapsAsIamProviderException() {
      when(mockUsersResource.create(any())).thenThrow(new RuntimeException("connection refused"));

      assertThrows(IamProviderException.class, () -> provider.createUser(iamUser("alice")));
    }

    // updateUser

    @Test
    void updateUser_HappyPath_CallsKeycloakUpdate() {
      provider.updateUser(IAM_USER_ID, iamUser("alice"));

      verify(mockUserResource).update(any());
    }

    @Test
    void updateUser_IamThrows_WrapsAsIamProviderException() {
      doThrow(new RuntimeException("IAM error")).when(mockUserResource).update(any());

      assertThrows(
          IamProviderException.class, () -> provider.updateUser(IAM_USER_ID, iamUser("alice")));
    }

    // deleteUser

    @Test
    void deleteUser_HappyPath_CallsKeycloakRemove() {
      provider.deleteUser(IAM_USER_ID);

      verify(mockUserResource).remove();
    }

    @Test
    void deleteUser_IamThrows_WrapsAsIamProviderException() {
      doThrow(new RuntimeException("not found")).when(mockUserResource).remove();

      assertThrows(IamProviderException.class, () -> provider.deleteUser(IAM_USER_ID));
    }

    // resetPassword

    @Test
    void resetPassword_TemporaryFalse_SetsCorrectCredential() {
      provider.resetPassword(IAM_USER_ID, "secret123", false);

      ArgumentCaptor<CredentialRepresentation> captor =
          ArgumentCaptor.forClass(CredentialRepresentation.class);
      verify(mockUserResource).resetPassword(captor.capture());
      CredentialRepresentation cred = captor.getValue();
      assertEquals("secret123", cred.getValue());
      assertFalse(cred.isTemporary());
      assertEquals(CredentialRepresentation.PASSWORD, cred.getType());
    }

    @Test
    void resetPassword_TemporaryTrue_SetsCorrectCredential() {
      provider.resetPassword(IAM_USER_ID, "temp-pass", true);

      ArgumentCaptor<CredentialRepresentation> captor =
          ArgumentCaptor.forClass(CredentialRepresentation.class);
      verify(mockUserResource).resetPassword(captor.capture());
      assertTrue(captor.getValue().isTemporary());
    }

    @Test
    void resetPassword_IamThrows_WrapsAsIamProviderException() {
      doThrow(new RuntimeException("IAM error")).when(mockUserResource).resetPassword(any());

      assertThrows(
          IamProviderException.class, () -> provider.resetPassword(IAM_USER_ID, "pass", false));
    }

    private IamUser iamUser(String username) {
      IamUser user = new IamUser();
      user.setUsername(username);
      user.setEmail(username + "@example.com");
      user.setEnabled(true);
      return user;
    }
  }

  // -------------------------------------------------------------------------
  // Role discovery — requires mocked Keycloak admin client
  // -------------------------------------------------------------------------

  @Nested
  @ExtendWith(MockitoExtension.class)
  class RoleDiscovery {

    @Mock private Keycloak mockKeycloak;
    @Mock private RealmResource mockRealm;
    @Mock private RolesResource mockRolesResource;
    @Mock private ClientsResource mockClientsResource;
    @Mock private ClientResource mockClientResource;
    @Mock private RolesResource mockClientRolesResource;

    private KeycloakIamProvider provider;

    private static final String CLIENT_UUID = "client-uuid-abc";

    @BeforeEach
    void setUp() throws Exception {
      provider = new KeycloakIamProvider(SERVER_URL, REALM, CLIENT_ID, CLIENT_SECRET);
      Field keycloakField = KeycloakIamProvider.class.getDeclaredField("keycloak");
      keycloakField.setAccessible(true);
      keycloakField.set(provider, mockKeycloak);

      lenient().when(mockKeycloak.realm(REALM)).thenReturn(mockRealm);
      lenient().when(mockRealm.roles()).thenReturn(mockRolesResource);
      lenient().when(mockRealm.clients()).thenReturn(mockClientsResource);
      lenient().when(mockClientsResource.get(CLIENT_UUID)).thenReturn(mockClientResource);
      lenient().when(mockClientResource.roles()).thenReturn(mockClientRolesResource);
    }

    @Test
    void listAvailableRoles_HappyPath_ReturnsRealmAndClientRoles() {
      RoleRepresentation realmRole = roleRep("offline_access");
      when(mockRolesResource.list()).thenReturn(List.of(realmRole));

      ClientRepresentation clientRep = clientRep(CLIENT_UUID, CLIENT_ID);
      when(mockClientsResource.findByClientId(CLIENT_ID)).thenReturn(List.of(clientRep));

      RoleRepresentation clientRole = roleRep("practitioners.edit");
      when(mockClientRolesResource.list()).thenReturn(List.of(clientRole));

      AvailableRolesResponse result = provider.listAvailableRoles();

      assertEquals(1, result.getRealmRoles().size());
      assertEquals("offline_access", result.getRealmRoles().get(0).getName());
      assertEquals(1, result.getClients().size());
      assertEquals(CLIENT_ID, result.getClients().get(0).getClientId());
      assertEquals(1, result.getClients().get(0).getRoles().size());
      assertEquals("practitioners.edit", result.getClients().get(0).getRoles().get(0).getName());
    }

    @Test
    void listAvailableRoles_ClientNotFound_ThrowsIamProviderException404() {
      when(mockRolesResource.list()).thenReturn(Collections.emptyList());
      when(mockClientsResource.findByClientId(CLIENT_ID)).thenReturn(Collections.emptyList());

      IamProviderException ex =
          assertThrows(IamProviderException.class, () -> provider.listAvailableRoles());
      assertEquals(404, ex.getStatusCode());
    }

    @Test
    void listAvailableRoles_NullClientRoles_TreatedAsEmptyList() {
      when(mockRolesResource.list()).thenReturn(Collections.emptyList());
      when(mockClientsResource.findByClientId(CLIENT_ID))
          .thenReturn(List.of(clientRep(CLIENT_UUID, CLIENT_ID)));
      when(mockClientRolesResource.list()).thenReturn(null);

      AvailableRolesResponse result = provider.listAvailableRoles();

      assertTrue(result.getClients().get(0).getRoles().isEmpty());
    }

    @Test
    void listAvailableRoles_IamThrows_WrapsAsIamProviderException() {
      when(mockRolesResource.list()).thenThrow(new RuntimeException("IAM unavailable"));

      assertThrows(IamProviderException.class, () -> provider.listAvailableRoles());
    }

    private RoleRepresentation roleRep(String name) {
      RoleRepresentation rep = new RoleRepresentation();
      rep.setName(name);
      return rep;
    }

    private ClientRepresentation clientRep(String id, String clientId) {
      ClientRepresentation rep = new ClientRepresentation();
      rep.setId(id);
      rep.setClientId(clientId);
      return rep;
    }
  }

  // -------------------------------------------------------------------------
  // Group management — requires mocked Keycloak admin client
  // -------------------------------------------------------------------------

  @Nested
  @ExtendWith(MockitoExtension.class)
  class GroupManagement {

    @Mock private Keycloak mockKeycloak;
    @Mock private RealmResource mockRealm;
    @Mock private GroupsResource mockGroupsResource;
    @Mock private GroupResource mockGroupResource;
    @Mock private RoleMappingResource mockRoleMappingResource;
    @Mock private RoleScopeResource mockRealmLevelRoles;
    @Mock private UsersResource mockUsersResource;
    @Mock private UserResource mockUserResource;
    @Mock private jakarta.ws.rs.core.Response mockResponse;

    private KeycloakIamProvider provider;

    private static final String GROUP_ID = "group-id-123";
    private static final String IAM_USER_ID = "user-id-456";

    @BeforeEach
    void setUp() throws Exception {
      provider = new KeycloakIamProvider(SERVER_URL, REALM, CLIENT_ID, CLIENT_SECRET);
      Field keycloakField = KeycloakIamProvider.class.getDeclaredField("keycloak");
      keycloakField.setAccessible(true);
      keycloakField.set(provider, mockKeycloak);

      lenient().when(mockKeycloak.realm(REALM)).thenReturn(mockRealm);
      lenient().when(mockRealm.groups()).thenReturn(mockGroupsResource);
      lenient().when(mockGroupsResource.group(GROUP_ID)).thenReturn(mockGroupResource);
      lenient().when(mockGroupResource.roles()).thenReturn(mockRoleMappingResource);
      lenient().when(mockRoleMappingResource.realmLevel()).thenReturn(mockRealmLevelRoles);
      lenient().when(mockRealm.users()).thenReturn(mockUsersResource);
      lenient().when(mockUsersResource.get(IAM_USER_ID)).thenReturn(mockUserResource);
    }

    // createGroup

    @Test
    void createGroup_NoRoles_CreatesGroupAndReturnsRepresentation() {
      IamGroup group = groupWithName("nurses");
      givenGroupCreationSucceeds(GROUP_ID);
      givenGroupRepresentation(GROUP_ID, "nurses", "/nurses");
      givenEmptyRoleMappings();

      IamGroupRepresentation result = provider.createGroup(group);

      verify(mockGroupsResource).add(argThat(rep -> "nurses".equals(rep.getName())));
      assertEquals(GROUP_ID, result.getId());
      assertEquals("nurses", result.getName());
      assertEquals(Collections.emptyList(), result.getRealmRoles());
      assertEquals(Collections.emptyMap(), result.getClientRoles());
    }

    @Test
    void createGroup_IamReturnsNon201_ThrowsIamProviderExceptionWithUpstreamStatus() {
      IamGroup group = groupWithName("nurses");
      when(mockGroupsResource.add(any())).thenReturn(mockResponse);
      when(mockResponse.getStatus()).thenReturn(409);
      when(mockResponse.readEntity(String.class)).thenReturn("Conflict");

      IamProviderException ex =
          assertThrows(IamProviderException.class, () -> provider.createGroup(group));
      assertEquals(409, ex.getStatusCode());
    }

    @Test
    void createGroup_RoleAssignmentFails_RollsBackGroupAndThrows() {
      IamGroup group = groupWithName("nurses");
      group.setRealmRoles(List.of("admin-role"));
      givenGroupCreationSucceeds(GROUP_ID);

      RoleRepresentation roleRep = new RoleRepresentation();
      roleRep.setName("admin-role");
      var mockRolesResource = mock(org.keycloak.admin.client.resource.RolesResource.class);
      var mockRoleResource = mock(org.keycloak.admin.client.resource.RoleResource.class);
      when(mockRealm.roles()).thenReturn(mockRolesResource);
      when(mockRolesResource.get("admin-role")).thenReturn(mockRoleResource);
      when(mockRoleResource.toRepresentation()).thenReturn(roleRep);
      doThrow(new RuntimeException("role assignment failed")).when(mockRealmLevelRoles).add(any());

      assertThrows(IamProviderException.class, () -> provider.createGroup(group));
      verify(mockGroupResource).remove();
    }

    @Test
    void createGroup_RoleAssignmentFails_RollbackAlsoFails_StillThrows() {
      IamGroup group = groupWithName("nurses");
      group.setRealmRoles(List.of("admin-role"));
      givenGroupCreationSucceeds(GROUP_ID);

      RoleRepresentation roleRep = new RoleRepresentation();
      roleRep.setName("admin-role");
      var mockRolesResource = mock(org.keycloak.admin.client.resource.RolesResource.class);
      var mockRoleResource = mock(org.keycloak.admin.client.resource.RoleResource.class);
      when(mockRealm.roles()).thenReturn(mockRolesResource);
      when(mockRolesResource.get("admin-role")).thenReturn(mockRoleResource);
      when(mockRoleResource.toRepresentation()).thenReturn(roleRep);
      doThrow(new RuntimeException("role assignment failed")).when(mockRealmLevelRoles).add(any());
      doThrow(new RuntimeException("rollback failed")).when(mockGroupResource).remove();

      assertThrows(IamProviderException.class, () -> provider.createGroup(group));
    }

    // getGroup

    @Test
    void getGroup_ReturnsFullRepresentation() {
      givenGroupRepresentation(GROUP_ID, "nurses", "/nurses");
      givenEmptyRoleMappings();

      IamGroupRepresentation result = provider.getGroup(GROUP_ID);

      assertEquals(GROUP_ID, result.getId());
      assertEquals("nurses", result.getName());
      assertEquals(Collections.emptyList(), result.getRealmRoles());
    }

    @Test
    void getGroup_IamFails_ThrowsIamProviderException() {
      when(mockGroupResource.toRepresentation()).thenThrow(new RuntimeException("not found"));

      assertThrows(IamProviderException.class, () -> provider.getGroup(GROUP_ID));
    }

    // listGroups

    @Test
    void listGroups_ReturnsBasicRepresentationForEachGroup() {
      GroupRepresentation g1 = keycloakGroupRep("gid-1", "nurses", "/nurses");
      GroupRepresentation g2 = keycloakGroupRep("gid-2", "doctors", "/doctors");
      when(mockGroupsResource.groups()).thenReturn(List.of(g1, g2));

      List<IamGroupRepresentation> result = provider.listGroups();

      assertEquals(2, result.size());
      assertEquals("gid-1", result.get(0).getId());
      assertEquals("nurses", result.get(0).getName());
      assertEquals("gid-2", result.get(1).getId());
    }

    @Test
    void listGroups_IamFails_ThrowsIamProviderException() {
      when(mockGroupsResource.groups()).thenThrow(new RuntimeException("IAM unavailable"));

      assertThrows(IamProviderException.class, () -> provider.listGroups());
    }

    // updateGroup

    @Test
    void updateGroup_NoRoles_UpdatesNameAndReplacesRoles() {
      IamGroup group = groupWithName("updated-name");
      GroupRepresentation existingRep = keycloakGroupRep(GROUP_ID, "old-name", "/old-name");
      when(mockGroupResource.toRepresentation()).thenReturn(existingRep);
      when(mockRealmLevelRoles.listAll()).thenReturn(Collections.emptyList());
      when(mockRoleMappingResource.getAll()).thenReturn(new MappingsRepresentation());

      provider.updateGroup(GROUP_ID, group);

      verify(mockGroupResource).update(argThat(rep -> "updated-name".equals(rep.getName())));
    }

    @Test
    void updateGroup_IamFails_ThrowsIamProviderException() {
      IamGroup group = groupWithName("updated-name");
      when(mockGroupResource.toRepresentation()).thenThrow(new RuntimeException("IAM error"));

      assertThrows(IamProviderException.class, () -> provider.updateGroup(GROUP_ID, group));
    }

    // deleteGroup

    @Test
    void deleteGroup_CallsRemoveOnGroup() {
      provider.deleteGroup(GROUP_ID);

      verify(mockGroupResource).remove();
    }

    @Test
    void deleteGroup_IamFails_ThrowsIamProviderException() {
      doThrow(new RuntimeException("not found")).when(mockGroupResource).remove();

      assertThrows(IamProviderException.class, () -> provider.deleteGroup(GROUP_ID));
    }

    @Test
    void deleteGroup_WebApplicationException_WrapsWithUpstreamStatusCode() {
      doThrow(new WebApplicationException(404)).when(mockGroupResource).remove();

      IamProviderException ex =
          assertThrows(IamProviderException.class, () -> provider.deleteGroup(GROUP_ID));
      assertEquals(404, ex.getStatusCode());
    }

    // addUserToGroup

    @Test
    void addUserToGroup_CallsJoinGroup() {
      provider.addUserToGroup(IAM_USER_ID, GROUP_ID);

      verify(mockUserResource).joinGroup(GROUP_ID);
    }

    @Test
    void addUserToGroup_IamFails_ThrowsIamProviderException() {
      doThrow(new RuntimeException("IAM error")).when(mockUserResource).joinGroup(GROUP_ID);

      assertThrows(
          IamProviderException.class, () -> provider.addUserToGroup(IAM_USER_ID, GROUP_ID));
    }

    // removeUserFromGroup

    @Test
    void removeUserFromGroup_CallsLeaveGroup() {
      provider.removeUserFromGroup(IAM_USER_ID, GROUP_ID);

      verify(mockUserResource).leaveGroup(GROUP_ID);
    }

    @Test
    void removeUserFromGroup_IamFails_ThrowsIamProviderException() {
      doThrow(new RuntimeException("IAM error")).when(mockUserResource).leaveGroup(GROUP_ID);

      assertThrows(
          IamProviderException.class, () -> provider.removeUserFromGroup(IAM_USER_ID, GROUP_ID));
    }

    // getUserGroups

    @Test
    void getUserGroups_ReturnsBasicRepresentationForEachGroup() {
      GroupRepresentation g1 = keycloakGroupRep("gid-1", "nurses", "/nurses");
      GroupRepresentation g2 = keycloakGroupRep("gid-2", "doctors", "/doctors");
      when(mockUserResource.groups()).thenReturn(List.of(g1, g2));

      List<IamGroupRepresentation> result = provider.getUserGroups(IAM_USER_ID);

      assertEquals(2, result.size());
      assertEquals("gid-1", result.get(0).getId());
      assertEquals("gid-2", result.get(1).getId());
    }

    @Test
    void getUserGroups_IamFails_ThrowsIamProviderException() {
      when(mockUserResource.groups()).thenThrow(new RuntimeException("IAM error"));

      assertThrows(IamProviderException.class, () -> provider.getUserGroups(IAM_USER_ID));
    }

    // Helpers

    private IamGroup groupWithName(String name) {
      IamGroup group = new IamGroup();
      group.setName(name);
      return group;
    }

    private GroupRepresentation keycloakGroupRep(String id, String name, String path) {
      GroupRepresentation rep = new GroupRepresentation();
      rep.setId(id);
      rep.setName(name);
      rep.setPath(path);
      return rep;
    }

    private void givenGroupCreationSucceeds(String groupId) {
      when(mockGroupsResource.add(any())).thenReturn(mockResponse);
      when(mockResponse.getStatus()).thenReturn(201);
      when(mockResponse.getHeaderString("Location"))
          .thenReturn(SERVER_URL + "/auth/admin/realms/" + REALM + "/groups/" + groupId);
    }

    private void givenGroupRepresentation(String id, String name, String path) {
      when(mockGroupResource.toRepresentation()).thenReturn(keycloakGroupRep(id, name, path));
    }

    private void givenEmptyRoleMappings() {
      when(mockRoleMappingResource.getAll()).thenReturn(new MappingsRepresentation());
    }
  }

  // -------------------------------------------------------------------------
  // Token role extraction — pure map parsing, no Keycloak dependency
  // -------------------------------------------------------------------------

  @Nested
  class ExtractRoles {

    private KeycloakIamProvider provider;

    @BeforeEach
    void setUp() {
      provider = new KeycloakIamProvider(SERVER_URL, REALM, CLIENT_ID, CLIENT_SECRET);
    }

    @Test
    void extractRoles_HappyPath_ReturnsAllRoles() {
      Map<String, Object> claims = claimsWithRoles(List.of("users.manage", "groups.view"));

      Set<String> roles = provider.extractRolesFromToken(claims);

      assertEquals(Set.of("users.manage", "groups.view"), roles);
    }

    @Test
    void extractRoles_SingleRole_ReturnsSingletonSet() {
      Map<String, Object> claims = claimsWithRoles(List.of("roles.view"));

      Set<String> roles = provider.extractRolesFromToken(claims);

      assertEquals(Set.of("roles.view"), roles);
    }

    @Test
    void extractRoles_EmptyRolesList_ReturnsEmptySet() {
      Map<String, Object> claims = claimsWithRoles(Collections.emptyList());

      Set<String> roles = provider.extractRolesFromToken(claims);

      assertTrue(roles.isEmpty());
    }

    @Test
    void extractRoles_MissingRealmAccess_ReturnsEmptySet() {
      Map<String, Object> claims = new HashMap<>();
      claims.put("sub", "user-123");

      Set<String> roles = provider.extractRolesFromToken(claims);

      assertTrue(roles.isEmpty());
    }

    @Test
    void extractRoles_RealmAccessIsNotAMap_ReturnsEmptySet() {
      Map<String, Object> claims = new HashMap<>();
      claims.put("realm_access", "not-a-map");

      Set<String> roles = provider.extractRolesFromToken(claims);

      assertTrue(roles.isEmpty());
    }

    @Test
    void extractRoles_RealmAccessMissingRolesKey_ReturnsEmptySet() {
      Map<String, Object> realmAccess = new HashMap<>();
      realmAccess.put("something_else", List.of("value"));
      Map<String, Object> claims = new HashMap<>();
      claims.put("realm_access", realmAccess);

      Set<String> roles = provider.extractRolesFromToken(claims);

      assertTrue(roles.isEmpty());
    }

    @Test
    void extractRoles_RolesValueIsNotAList_ReturnsEmptySet() {
      Map<String, Object> realmAccess = new HashMap<>();
      realmAccess.put("roles", "users.manage");
      Map<String, Object> claims = new HashMap<>();
      claims.put("realm_access", realmAccess);

      Set<String> roles = provider.extractRolesFromToken(claims);

      assertTrue(roles.isEmpty());
    }

    @Test
    void extractRoles_RolesListContainsNonStringItems_SkipsNonStrings() {
      Map<String, Object> realmAccess = new HashMap<>();
      realmAccess.put("roles", List.of("users.manage", 42, true, "groups.view"));
      Map<String, Object> claims = new HashMap<>();
      claims.put("realm_access", realmAccess);

      Set<String> roles = provider.extractRolesFromToken(claims);

      assertEquals(Set.of("users.manage", "groups.view"), roles);
    }

    @Test
    void extractRoles_RolesListContainsOnlyNonStringItems_ReturnsEmptySet() {
      Map<String, Object> realmAccess = new HashMap<>();
      realmAccess.put("roles", List.of(1, 2, true));
      Map<String, Object> claims = new HashMap<>();
      claims.put("realm_access", realmAccess);

      Set<String> roles = provider.extractRolesFromToken(claims);

      assertTrue(roles.isEmpty());
    }

    @Test
    void extractRoles_EmptyClaims_ReturnsEmptySet() {
      Set<String> roles = provider.extractRolesFromToken(Collections.emptyMap());

      assertTrue(roles.isEmpty());
    }

    private Map<String, Object> claimsWithRoles(List<?> roleValues) {
      Map<String, Object> realmAccess = new HashMap<>();
      realmAccess.put("roles", roleValues);
      Map<String, Object> claims = new HashMap<>();
      claims.put("realm_access", realmAccess);
      return claims;
    }
  }
}
