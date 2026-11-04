package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.cronn.keycloak.provisioning.connection.DefaultKeycloakConnectionManager;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.ProtocolMapperRepresentation;

class ClientAdministrationTest extends SharedKeycloakTest {

  @Test
  void createsAClientWithItsScopesAndMappers() {
    ClientRepresentation portal = client("acme-portal");
    portal.setDefaultClientScopes(List.of("profile", "email"));
    portal.setOptionalClientScopes(List.of());
    portal.setProtocolMappers(List.of(hardcodedClaim("tenant")));

    String id = fromAdministration(clients -> clients.createClient(portal));

    assertThat(realm().clients().get(id).toRepresentation().getClientId()).isEqualTo("acme-portal");
    withRealmManager(this::validateCustomClients);
  }

  @Test
  void appliesTheSameSecurityDefaultsAsProvisioning() {
    String id = fromAdministration(clients -> clients.createClient(client("acme-portal")));

    assertThat(realm().clients().get(id).toRepresentation().isFullScopeAllowed()).isFalse();
  }

  @Test
  void appliesTheSameValidationAsProvisioning() {
    ClientRepresentation wildcard = client("acme-portal");
    wildcard.setRedirectUris(List.of("https://acme.example.com/*"));

    assertThatThrownBy(() -> withAdministration(clients -> clients.createClient(wildcard)))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("wildcard");
  }

  @Test
  void refusesToCreateAClientThatExists() {
    withAdministration(clients -> clients.createClient(client("acme-portal")));

    assertThatThrownBy(
            () -> withAdministration(clients -> clients.createClient(client("acme-portal"))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("'acme-portal' already exists");
  }

  @Test
  void refusesAPrefixedClientId() {
    assertThatThrownBy(
            () -> withAdministration(clients -> clients.createClient(client("system-portal"))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("managed prefix");
  }

  @Test
  void updatesAClientMatchedByClientId() {
    withAdministration(clients -> clients.createClient(client("acme-portal")));
    ClientRepresentation changed = client("acme-portal");
    changed.setDescription("changed");

    withAdministration(clients -> clients.updateClient(changed));

    assertThat(findClient("acme-portal").getDescription()).isEqualTo("changed");
  }

  @Test
  void refusesToUpdateAClientThatDoesNotExist() {
    assertThatThrownBy(
            () -> withAdministration(clients -> clients.updateClient(client("acme-portal"))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("No client 'acme-portal'");
  }

  @Test
  void findsAClientByClientId() {
    String id = fromAdministration(clients -> clients.createClient(client("acme-portal")));

    withAdministration(
        clients -> {
          assertThat(clients.findClientByClientId("acme-portal"))
              .get()
              .extracting(ClientRepresentation::getId)
              .isEqualTo(id);
          assertThat(clients.findClientByClientId("account")).isPresent();
          assertThat(clients.findClientByClientId("acme")).isEmpty();
        });
  }

  @Test
  void deletesAClient() {
    withAdministration(clients -> clients.createClient(client("acme-portal")));

    withAdministration(clients -> clients.deleteClient("acme-portal"));

    assertThat(realm().clients().findByClientId("acme-portal")).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {"account", "broker", "realm-management", "admin-cli"})
  void refusesToChangeOrDeleteAClientKeycloakProvides(String clientId) {
    assertThatThrownBy(() -> withAdministration(clients -> clients.deleteClient(clientId)))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("Keycloak's own");
    assertThatThrownBy(() -> withAdministration(clients -> clients.updateClient(client(clientId))))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("Keycloak's own");
  }

  /** Creating a realm makes Keycloak add a {@code <realm>-realm} client to master. */
  @Test
  void refusesToDeleteTheAdminClientOfARealmInMaster() {
    try (DefaultKeycloakConnectionManager connectionManager = connectionManager()) {
      ClientAdministration master =
          new ClientAdministration(connectionManager.realmManager("master"));

      assertThatThrownBy(() -> master.deleteClient(REALM + "-realm"))
          .isInstanceOf(KeycloakException.class)
          .hasMessageContaining("Keycloak's own");
    }
  }

  @Test
  void refusesToChangeOrDeleteTheProvisioningClient() {
    assertThatThrownBy(
            () -> withAdministration(clients -> clients.deleteClient("keycloak-provisioning")))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("provisioning client");
  }

  @Test
  void provisioningLeavesClientsCreatedOnDemandAlone() {
    withAdministration(clients -> clients.createClient(client("acme-portal")));

    withRealmManager(rm -> rm.provisionManagedClients(List.of()));

    assertThat(realm().clients().findByClientId("acme-portal")).hasSize(1);
  }

  private static ClientRepresentation client(String clientId) {
    ClientRepresentation client = new ClientRepresentation();
    client.setClientId(clientId);
    client.setPublicClient(true);
    client.setRedirectUris(List.of("https://acme.example.com/callback"));
    return client;
  }

  private static ProtocolMapperRepresentation hardcodedClaim(String name) {
    ProtocolMapperRepresentation mapper = new ProtocolMapperRepresentation();
    mapper.setName(name);
    mapper.setProtocol("openid-connect");
    mapper.setProtocolMapper("oidc-hardcoded-claim-mapper");
    mapper.setConfig(Map.of("claim.name", name, "claim.value", "42", "access.token.claim", "true"));
    return mapper;
  }

  private ClientRepresentation findClient(String clientId) {
    return realm().clients().findByClientId(clientId).getFirst();
  }

  private RealmResource realm() {
    return adminClient().realm(REALM);
  }

  private <T> T fromAdministration(Function<ClientAdministration, T> action) {
    Object[] result = new Object[1];
    withRealmManager(rm -> result[0] = action.apply(new ClientAdministration(rm)));
    @SuppressWarnings("unchecked")
    T typed = (T) result[0];
    return typed;
  }

  private void withAdministration(Consumer<ClientAdministration> action) {
    withRealmManager(rm -> action.accept(new ClientAdministration(rm)));
  }
}
