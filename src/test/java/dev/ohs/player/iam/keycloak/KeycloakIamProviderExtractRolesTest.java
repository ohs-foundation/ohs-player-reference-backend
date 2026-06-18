package dev.ohs.player.iam.keycloak;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KeycloakIamProviderExtractRolesTest {

  private KeycloakIamProvider provider;

  @BeforeEach
  void setUp() {
    provider = new KeycloakIamProvider("http://keycloak", "test-realm", "client-id", "secret");
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

  private static Map<String, Object> claimsWithRoles(List<?> roleValues) {
    Map<String, Object> realmAccess = new HashMap<>();
    realmAccess.put("roles", roleValues);
    Map<String, Object> claims = new HashMap<>();
    claims.put("realm_access", realmAccess);
    return claims;
  }
}
