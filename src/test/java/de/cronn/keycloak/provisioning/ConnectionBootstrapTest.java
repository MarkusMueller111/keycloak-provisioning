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
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

/** Bootstrapping happens once per Keycloak, so every test here needs an untouched one. */
class ConnectionBootstrapTest extends OwnKeycloakTest {

  private static final KeycloakProvisioningClientConfig CLIENT_CONFIG =
      new KeycloakProvisioningClientConfig(
          SystemClientNaming.standard().clientId("provisioning"), "secret");

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
      assertThat(tokenClientId(connectionManager)).isEqualTo("system-provisioning");
    }
  }

  @Test
  void reconnectsAsTheProvisioningClientOnceBootstrapped() throws JsonProcessingException {
    try (DefaultKeycloakConnectionManager first = connectionManager()) {
      assertThat(tokenClientId(first)).isEqualTo("system-provisioning");
    }

    try (DefaultKeycloakConnectionManager second = connectionManager()) {
      assertThat(tokenClientId(second)).isEqualTo("system-provisioning");
      validateServiceAccountUsers(second.realmManager("master"));
    }
  }

  @Test
  void grantsTheAdminRoleToTheProvisioningServiceAccount() {
    try (DefaultKeycloakConnectionManager connectionManager = connectionManager()) {
      RealmManager master = connectionManager.realmManager("master");
      UserRepresentation serviceAccount =
          master.getClientByClientId("system-provisioning").orElseThrow().getServiceAccountUser();

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
        .hasMessageContaining("system-provisioning")
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
