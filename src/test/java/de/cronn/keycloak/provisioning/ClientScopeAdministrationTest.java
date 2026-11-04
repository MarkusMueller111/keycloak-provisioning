package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ClientScopeRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;

class ClientScopeAdministrationTest extends SharedKeycloakTest {

  @Test
  void createsAScopeWithItsMappers() {
    ClientScopeRepresentation scope = scope("acme");
    scope.setProtocolMappers(List.of(hardcodedClaim("tenant")));

    String id = fromAdministration(scopes -> scopes.createClientScope(scope));

    assertThat(realm().clientScopes().get(id).toRepresentation().getName()).isEqualTo("acme");
    withRealmManager(this::validateCustomClientScopes);
  }

  @Test
  void refusesToCreateAScopeThatExists() {
    withAdministration(scopes -> scopes.createClientScope(scope("acme")));

    assertThatThrownBy(() -> withAdministration(scopes -> scopes.createClientScope(scope("acme"))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("'acme' already exists");
  }

  @Test
  void refusesAPrefixedName() {
    assertThatThrownBy(
            () -> withAdministration(scopes -> scopes.createClientScope(scope("system-acme"))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("managed prefix");
  }

  @Test
  void appliesTheSameValidationAsProvisioning() {
    ClientScopeRepresentation withoutProtocol = scope("acme");
    withoutProtocol.setProtocol(null);

    assertThatThrownBy(
            () -> withAdministration(scopes -> scopes.createClientScope(withoutProtocol)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("declares no protocol");
  }

  @Test
  void updatesAScopeMatchedByName() {
    withAdministration(scopes -> scopes.createClientScope(scope("acme")));
    ClientScopeRepresentation changed = scope("acme");
    changed.setDescription("changed");

    withAdministration(scopes -> scopes.updateClientScope(changed));

    assertThat(findScope("acme").getDescription()).isEqualTo("changed");
  }

  @Test
  void refusesToUpdateAScopeThatDoesNotExist() {
    assertThatThrownBy(() -> withAdministration(scopes -> scopes.updateClientScope(scope("acme"))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("No client scope 'acme'");
  }

  @Test
  void deletesAScopeAndItsAssignments() {
    withAdministration(scopes -> scopes.createClientScope(scope("acme")));
    ClientRepresentation portal = new ClientRepresentation();
    portal.setClientId("acme-portal");
    portal.setDefaultClientScopes(List.of("acme"));
    portal.setOptionalClientScopes(List.of());
    withRealmManager(rm -> new ClientAdministration(rm).createClient(portal));

    withAdministration(scopes -> scopes.deleteClientScope("acme"));

    assertThat(scopeNames()).doesNotContain("acme");
    String portalId = realm().clients().findByClientId("acme-portal").getFirst().getId();
    assertThat(realm().clients().get(portalId).getDefaultClientScopes()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"profile", "roles", "AuthnContextClassRef"})
  void refusesToChangeOrDeleteAScopeKeycloakProvides(String name) {
    assertThatThrownBy(() -> withAdministration(scopes -> scopes.deleteClientScope(name)))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("Keycloak's own");
    assertThatThrownBy(() -> withAdministration(scopes -> scopes.updateClientScope(scope(name))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("Keycloak's own");
  }

  /** Fails when a Keycloak upgrade adds a scope of its own, which would then be unprotected. */
  @Test
  void knowsEveryScopeOfAFreshRealmAsKeycloaksOwn() {
    assertThat(scopeNames()).allMatch(KeycloakProvidedClientScope::isKeycloakProvided);
  }

  @Test
  void provisioningLeavesScopesCreatedOnDemandAlone() {
    withAdministration(scopes -> scopes.createClientScope(scope("acme")));

    withRealmManager(rm -> rm.provisionManagedClientScopes(List.of()));

    assertThat(scopeNames()).contains("acme");
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
    mapper.setConfig(Map.of("claim.name", name, "claim.value", "42", "access.token.claim", "true"));
    return mapper;
  }

  private ClientScopeRepresentation findScope(String name) {
    return realm().clientScopes().findAll().stream()
        .filter(scope -> name.equals(scope.getName()))
        .findFirst()
        .orElseThrow();
  }

  private List<String> scopeNames() {
    return realm().clientScopes().findAll().stream()
        .map(ClientScopeRepresentation::getName)
        .toList();
  }

  private RealmResource realm() {
    return adminClient().realm(REALM);
  }

  private <T> T fromAdministration(Function<ClientScopeAdministration, T> action) {
    Object[] result = new Object[1];
    withRealmManager(rm -> result[0] = action.apply(new ClientScopeAdministration(rm)));
    @SuppressWarnings("unchecked")
    T typed = (T) result[0];
    return typed;
  }

  private void withAdministration(Consumer<ClientScopeAdministration> action) {
    withRealmManager(rm -> action.accept(new ClientScopeAdministration(rm)));
  }
}
