package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import de.cronn.keycloak.provisioning.connection.DefaultKeycloakConnectionManager;
import de.cronn.keycloak.provisioning.connection.KeycloakConnectionManager;
import de.cronn.keycloak.provisioning.connection.KeycloakDefaultConnectionConfig;
import de.cronn.keycloak.provisioning.connection.admincli.KeycloakAdminCliConnectionManager;
import de.cronn.keycloak.provisioning.connection.admincli.KeycloakAdminCredentialsConfig;
import de.cronn.keycloak.provisioning.connection.client.KeycloakProvisioningClientConfig;
import de.cronn.keycloak.provisioning.connection.client.KeycloakProvisioningConnectionManager;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.WebApplicationException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.RoleScopeResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

/** Bootstrapping happens once per Keycloak, so every test here needs an untouched one. */
class ConnectionBootstrapTest extends OwnKeycloakTest {

  private static final KeycloakProvisioningClientConfig CLIENT_CONFIG =
      new KeycloakProvisioningClientConfig("keycloak-provisioning", "secret");

  /**
   * A consumer whose own convention names system clients with the managed prefix must be able to
   * name the provisioning client that way too.
   */
  @Test
  void keepsAProvisioningClientWhoseIdCarriesTheManagedPrefix() {
    KeycloakProvisioningClientConfig prefixed =
        new KeycloakProvisioningClientConfig("system-keycloak-provisioning", "secret");

    try (DefaultKeycloakConnectionManager connectionManager =
        new DefaultKeycloakConnectionManager(
            new KeycloakDefaultConnectionConfig(
                serverUrl(),
                new KeycloakAdminCredentialsConfig(ADMIN_USERNAME, ADMIN_PASSWORD),
                prefixed))) {
      RealmManager realmManager = connectionManager.realmManager("master");

      assertThat(clientIds(realmManager)).contains("system-keycloak-provisioning");

      // Declaring none of them must not delete the identity this is connected as.
      realmManager.provisionManagedClients(List.of());

      assertThat(clientIds(realmManager)).contains("system-keycloak-provisioning");
    }
  }

  /** Connecting directly, without the bootstrap manager, fences the same client id. */
  @Test
  void keepsAPrefixedProvisioningClientWhenConnectedDirectly() {
    KeycloakProvisioningClientConfig prefixed =
        new KeycloakProvisioningClientConfig("system-keycloak-provisioning", "secret");

    try (DefaultKeycloakConnectionManager bootstrap =
        new DefaultKeycloakConnectionManager(
            new KeycloakDefaultConnectionConfig(
                serverUrl(),
                new KeycloakAdminCredentialsConfig(ADMIN_USERNAME, ADMIN_PASSWORD),
                prefixed))) {
      assertThat(clientIds(bootstrap.realmManager("master")))
          .contains("system-keycloak-provisioning");
    }

    try (KeycloakProvisioningConnectionManager direct =
        new KeycloakProvisioningConnectionManager(serverUrl(), prefixed)) {
      RealmManager realmManager = direct.realmManager("master");

      realmManager.provisionManagedClients(List.of());

      assertThat(clientIds(realmManager)).contains("system-keycloak-provisioning");
    }
  }

  /** The provisioning client can still authenticate, so Keycloak answers 403 rather than 401. */
  @Test
  void bootstrapsAgainWhenTheProvisioningClientLostItsAdminRole() {
    try (DefaultKeycloakConnectionManager connectionManager = connectionManager()) {
      assertThat(connectionManager.realmManager("master")).isNotNull();
    }

    RealmResource master = adminClient().realm("master");
    String id = master.clients().findByClientId("keycloak-provisioning").getFirst().getId();
    RoleScopeResource serviceAccountRoles =
        master
            .users()
            .get(master.clients().get(id).getServiceAccountUser().getId())
            .roles()
            .realmLevel();
    serviceAccountRoles.remove(
        serviceAccountRoles.listAll().stream()
            .filter(role -> RealmManager.ADMIN_REALM_ROLE.equals(role.getName()))
            .toList());

    try (DefaultKeycloakConnectionManager connectionManager = connectionManager()) {
      assertThat(connectionManager.realmManager("master").getRoles()).isNotEmpty();
    }
  }

  private static List<String> clientIds(RealmManager realmManager) {
    return realmManager.getRealmResource().clients().findAll().stream()
        .map(org.keycloak.representations.idm.ClientRepresentation::getClientId)
        .toList();
  }

  /**
   * Before the provisioning client exists nothing can authenticate as it, so the first connect has
   * to fall back to the bootstrap admin to create it.
   */
  @Test
  void bootstrapsProvisioningClientViaAdminCliOnFirstConnect() throws JsonProcessingException {
    assertThatThrownBy(() -> new KeycloakProvisioningConnectionManager(serverUrl(), CLIENT_CONFIG))
        .isInstanceOf(KeycloakException.class)
        .satisfies(
            thrown ->
                assertThat(ExceptionUtils.indexOfType(thrown, NotAuthorizedException.class))
                    .isNotNegative());

    try (KeycloakAdminCliConnectionManager adminCli =
        new KeycloakAdminCliConnectionManager(
            serverUrl(), new KeycloakAdminCredentialsConfig(ADMIN_USERNAME, ADMIN_PASSWORD))) {
      assertThat(tokenClientId(adminCli)).isEqualTo("admin-cli");
    }

    try (DefaultKeycloakConnectionManager connectionManager = connectionManager()) {
      assertThat(tokenClientId(connectionManager)).isEqualTo("keycloak-provisioning");
    }
  }

  @Test
  void reconnectsAsTheProvisioningClientOnceBootstrapped() throws JsonProcessingException {
    try (DefaultKeycloakConnectionManager first = connectionManager()) {
      assertThat(tokenClientId(first)).isEqualTo("keycloak-provisioning");
    }

    try (DefaultKeycloakConnectionManager second = connectionManager()) {
      assertThat(tokenClientId(second)).isEqualTo("keycloak-provisioning");
      validateServiceAccountUsers(second.realmManager("master"));
    }
  }

  @Test
  void sendsNoWritesWhenReconnecting() {
    connectionManager().close();

    assertSendsNoWrites(() -> connectionManager().close());
  }

  @Test
  void grantsTheAdminRoleToTheProvisioningServiceAccount() {
    try (DefaultKeycloakConnectionManager connectionManager = connectionManager()) {
      RealmManager master = connectionManager.realmManager("master");
      UserRepresentation serviceAccount =
          master.getClientByClientId("keycloak-provisioning").orElseThrow().getServiceAccountUser();

      assertThat(realmRoleNames(master, serviceAccount)).contains(RealmManager.ADMIN_REALM_ROLE);
    }
  }

  /**
   * Both paths fail, so the JAX-RS exception surfaces wrapped and names what was attempted. The
   * status Keycloak returns for bad admin credentials is not asserted: 26.0 answers 401, 26.7
   * answers 400.
   */
  @Test
  void reportsABootstrapFailureAsKeycloakException() {
    KeycloakDefaultConnectionConfig config =
        new KeycloakDefaultConnectionConfig(
            serverUrl(),
            new KeycloakAdminCredentialsConfig(ADMIN_USERNAME, "wrong-password"),
            CLIENT_CONFIG);

    assertThatThrownBy(() -> new DefaultKeycloakConnectionManager(config))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("keycloak-provisioning")
        .hasMessageContaining(serverUrl())
        .hasRootCauseInstanceOf(WebApplicationException.class);
  }

  private List<String> realmRoleNames(RealmManager realmManager, UserRepresentation user) {
    return realmManager.getUserResource(user.getId()).roles().realmLevel().listAll().stream()
        .map(RoleRepresentation::getName)
        .toList();
  }

  /** The client the token was issued to; admin-cli's token carries no user claims at all. */
  private String tokenClientId(KeycloakConnectionManager connectionManager)
      throws JsonProcessingException {
    String[] jwt =
        connectionManager.getKeycloakClient().tokenManager().getAccessTokenString().split("\\.");
    String payload = new String(Base64.getUrlDecoder().decode(jwt[1]), StandardCharsets.UTF_8);
    return jsonMapper().readTree(payload).get("azp").asText();
  }
}
