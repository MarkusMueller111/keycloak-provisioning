package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.cronn.keycloak.provisioning.Change.Target;
import de.cronn.keycloak.provisioning.Change.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;

class ClientScopeProvisioningTest extends SharedKeycloakTest {

  @Test
  void createsAndUpdatesTheConfiguredScopes() {
    withRealmManager(rm -> rm.provisionManagedClientScopes(List.of(scope("system-a"))));
    assertThat(scopeNames()).contains("system-a");

    ClientScopeRepresentation renamedDescription = scope("system-a");
    renamedDescription.setDescription("changed");
    withRealmManager(
        rm ->
            assertThat(rm.provisionManagedClientScopes(List.of(renamedDescription)).changes())
                .extracting(Change::target, Change::name, Change::type)
                .containsExactly(tuple(Target.CLIENT_SCOPE, "system-a", Type.UPDATED)));

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

  /** Keycloak cannot remove a scope attribute, so this pins the value standing in for absent. */
  @Test
  void restoresTheTokenScopeClaimWhenIncludeInTokenScopeIsNoLongerDeclared() {
    withRealmManager(
        rm -> {
          rm.provisionManagedClientScopes(List.of(scope("system-a")));
          rm.provisionManagedClients(List.of(tokenClient()));
        });
    assertThat(tokenScopes()).contains("system-a");

    ClientScopeRepresentation excluded = scope("system-a");
    excluded.setAttributes(Map.of("include.in.token.scope", "false"));
    withRealmManager(rm -> rm.provisionManagedClientScopes(List.of(excluded)));
    assertThat(tokenScopes()).doesNotContain("system-a");

    withRealmManager(rm -> rm.provisionManagedClientScopes(List.of(scope("system-a"))));

    assertThat(tokenScopes()).contains("system-a");
  }

  @Test
  void sendsNoWritesWhenRerunUnchanged() {
    withRealmManager(rm -> rm.provisionManagedClientScopes(List.of(scopeWithMapper())));

    withRealmManager(
        rm ->
            assertChangesNothing(
                () -> rm.provisionManagedClientScopes(List.of(scopeWithMapper()))));
  }

  @Test
  void sendsNoWritesWhenRerunWithoutMappers() {
    withRealmManager(rm -> rm.provisionManagedClientScopes(List.of(scopeWithMapper())));

    withRealmManager(
        rm ->
            assertChangesNothing(
                () -> rm.provisionManagedClientScopes(List.of(scope("system-a")))));
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

  private static ClientRepresentation tokenClient() {
    ClientRepresentation client = new ClientRepresentation();
    client.setClientId("system-token");
    client.setEnabled(true);
    client.setPublicClient(false);
    client.setServiceAccountsEnabled(true);
    client.setSecret("token-secret");
    client.setDefaultClientScopes(List.of("system-a"));
    client.setOptionalClientScopes(List.of());
    return client;
  }

  private List<String> tokenScopes() {
    try (Keycloak keycloak =
        KeycloakBuilder.builder()
            .serverUrl(serverUrl())
            .realm(REALM)
            .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
            .clientId("system-token")
            .clientSecret("token-secret")
            .build()) {
      return List.of(keycloak.tokenManager().getAccessToken().getScope().split(" "));
    }
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
