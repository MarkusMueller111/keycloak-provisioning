package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;

class ClientScopeProvisioningTest extends SharedKeycloakTest {

  @Test
  void createsAndUpdatesTheConfiguredScopes() {
    withRealmManager(rm -> rm.provisionManagedClientScopes(List.of(scope("system-a"))));
    assertThat(scopeNames()).contains("system-a");

    ClientScopeRepresentation renamedDescription = scope("system-a");
    renamedDescription.setDescription("changed");
    withRealmManager(rm -> rm.provisionManagedClientScopes(List.of(renamedDescription)));

    assertThat(findScope("system-a").getDescription()).isEqualTo("changed");
  }

  @Test
  void deletesScopesThatAreNoLongerConfigured() {
    withRealmManager(
        rm -> rm.provisionManagedClientScopes(List.of(scope("system-a"), scope("system-b"))));

    withRealmManager(rm -> rm.provisionManagedClientScopes(List.of(scope("system-a"))));

    assertThat(scopeNames()).contains("system-a").doesNotContain("system-b");
  }

  /**
   * Deleting one of these strips claims from every token in the realm while login keeps working, so
   * the marker rather than the caller's list has to decide what is in scope.
   */
  @Test
  void neverDeletesAScopeKeycloakProvided() {
    withRealmManager(rm -> rm.provisionManagedClientScopes(List.of(scope("system-a"))));

    assertThat(scopeNames()).contains("profile", "email", "roles", "offline_access");
  }

  /** A scope's own update ignores its mappers, so they need a pass of their own. */
  @Test
  void provisionsTheMappersOfAManagedScope() {
    ClientScopeRepresentation withMappers = scope("system-a");
    withMappers.setProtocolMappers(List.of(hardcodedClaim("kept"), hardcodedClaim("dropped")));
    withRealmManager(rm -> rm.provisionManagedClientScopes(List.of(withMappers)));
    assertThat(mapperNames("system-a")).containsExactlyInAnyOrder("kept", "dropped");

    ClientScopeRepresentation fewer = scope("system-a");
    fewer.setProtocolMappers(List.of(hardcodedClaim("kept")));
    withRealmManager(rm -> rm.provisionManagedClientScopes(List.of(fewer)));

    assertThat(mapperNames("system-a")).containsExactly("kept");
  }

  @Test
  void updatesTheScopeAndItsMapperAgainWhenRerunUnchanged() {
    withRealmManager(rm -> rm.provisionManagedClientScopes(List.of(scopeWithMapper())));

    withRealmManager(
        rm ->
            assertSendsOnlyWrites(
                () -> rm.provisionManagedClientScopes(List.of(scopeWithMapper())),
                "PUT /admin/realms/test/client-scopes/<id>",
                "PUT /admin/realms/test/client-scopes/<id>/protocol-mappers/models/<id>"));
  }

  @Test
  void updatesTheScopeAgainWhenRerunWithoutMappers() {
    withRealmManager(rm -> rm.provisionManagedClientScopes(List.of(scopeWithMapper())));

    withRealmManager(
        rm ->
            assertSendsOnlyWrites(
                () -> rm.provisionManagedClientScopes(List.of(scope("system-a"))),
                "PUT /admin/realms/test/client-scopes/<id>"));
  }

  private static ClientScopeRepresentation scopeWithMapper() {
    ClientScopeRepresentation scope = scope("system-a");
    scope.setProtocolMappers(List.of(hardcodedClaim("kept")));
    return scope;
  }

  private static ClientScopeRepresentation scope(String name) {
    ClientScopeRepresentation scope = new ClientScopeRepresentation();
    scope.setName(name);
    scope.setProtocol("openid-connect");
    return scope;
  }

  private static ProtocolMapperRepresentation hardcodedClaim(String name) {
    ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
    mapper.setName(name);
    mapper.setProtocol("openid-connect");
    mapper.setProtocolMapper("oidc-hardcoded-claim-mapper");
    mapper.setConfig(
        new LinkedHashMap<>(
            Map.of("claim.name", name, "claim.value", "x", "access.token.claim", "true")));
    return mapper;
  }

  private List<String> scopeNames() {
    return adminClient().realm(REALM).clientScopes().findAll().stream()
        .map(ClientScopeRepresentation::getName)
        .toList();
  }

  private ClientScopeRepresentation findScope(String name) {
    return adminClient().realm(REALM).clientScopes().findAll().stream()
        .filter(scope -> name.equals(scope.getName()))
        .findFirst()
        .orElseThrow();
  }

  private List<String> mapperNames(String scopeName) {
    List<ProtocolMapperRepresentation> mappers =
        adminClient()
            .realm(REALM)
            .clientScopes()
            .get(findScope(scopeName).getId())
            .getProtocolMappers()
            .getMappers();
    return mappers == null
        ? List.of()
        : mappers.stream().map(ProtocolMapperRepresentation::getName).toList();
  }
}
