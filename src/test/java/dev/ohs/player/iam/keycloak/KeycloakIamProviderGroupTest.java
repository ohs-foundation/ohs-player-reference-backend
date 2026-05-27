package dev.ohs.player.iam.keycloak;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.ohs.player.iam.IamGroup;
import dev.ohs.player.iam.IamGroupRepresentation;
import dev.ohs.player.iam.IamProviderException;
import jakarta.ws.rs.WebApplicationException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.GroupResource;
import org.keycloak.admin.client.resource.GroupsResource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleMappingResource;
import org.keycloak.admin.client.resource.RoleScopeResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.MappingsRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KeycloakIamProviderGroupTest {

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

  private static final String REALM = "test-realm";
  private static final String GROUP_ID = "group-id-123";
  private static final String IAM_USER_ID = "user-id-456";

  @BeforeEach
  void setUp() throws Exception {
    provider = new KeycloakIamProvider("http://keycloak", REALM, "client-id", "client-secret");
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

  // -------------------------------------------------------------------------
  // createGroup
  // -------------------------------------------------------------------------

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

  // -------------------------------------------------------------------------
  // getGroup
  // -------------------------------------------------------------------------

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

  // -------------------------------------------------------------------------
  // listGroups
  // -------------------------------------------------------------------------

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

  // -------------------------------------------------------------------------
  // updateGroup
  // -------------------------------------------------------------------------

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

  // -------------------------------------------------------------------------
  // deleteGroup
  // -------------------------------------------------------------------------

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

  // -------------------------------------------------------------------------
  // addUserToGroup
  // -------------------------------------------------------------------------

  @Test
  void addUserToGroup_CallsJoinGroup() {
    provider.addUserToGroup(IAM_USER_ID, GROUP_ID);

    verify(mockUserResource).joinGroup(GROUP_ID);
  }

  @Test
  void addUserToGroup_IamFails_ThrowsIamProviderException() {
    doThrow(new RuntimeException("IAM error")).when(mockUserResource).joinGroup(GROUP_ID);

    assertThrows(IamProviderException.class, () -> provider.addUserToGroup(IAM_USER_ID, GROUP_ID));
  }

  // -------------------------------------------------------------------------
  // removeUserFromGroup
  // -------------------------------------------------------------------------

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

  // -------------------------------------------------------------------------
  // getUserGroups
  // -------------------------------------------------------------------------

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

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

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
        .thenReturn("http://keycloak/auth/admin/realms/" + REALM + "/groups/" + groupId);
  }

  private void givenGroupRepresentation(String id, String name, String path) {
    when(mockGroupResource.toRepresentation()).thenReturn(keycloakGroupRep(id, name, path));
  }

  private void givenEmptyRoleMappings() {
    when(mockRoleMappingResource.getAll()).thenReturn(new MappingsRepresentation());
  }
}
