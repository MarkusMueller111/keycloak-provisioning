package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import de.cronn.keycloak.provisioning.connection.DefaultKeycloakConnectionManager;
import de.cronn.keycloak.provisioning.connection.KeycloakDefaultConnectionConfig;
import de.cronn.keycloak.provisioning.connection.admincli.KeycloakAdminCliConnectionManager;
import de.cronn.keycloak.provisioning.connection.admincli.KeycloakAdminCredentialsConfig;
import de.cronn.keycloak.provisioning.connection.client.KeycloakProvisioningClientConfig;
import de.cronn.keycloak.provisioning.connection.client.KeycloakProvisioningConnectionManager;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** The customizer only changes the connection, not Keycloak, so one shared container will do. */
class HttpClientCustomizerTest extends SharedKeycloakTest {

  private static final KeycloakProvisioningClientConfig CLIENT_CONFIG =
      new KeycloakProvisioningClientConfig("keycloak-provisioning", "secret");

  @Test
  void everyConnectionManagerAppliesTheHttpClientCustomizer() {
    RecordingFilter viaDefault = new RecordingFilter();
    RecordingFilter viaAdminCli = new RecordingFilter();
    RecordingFilter viaProvisioningClient = new RecordingFilter();

    KeycloakDefaultConnectionConfig config =
        KeycloakDefaultConnectionConfig.builder(
                serverUrl(),
                new KeycloakAdminCredentialsConfig(ADMIN_USERNAME, ADMIN_PASSWORD),
                CLIENT_CONFIG)
            .httpClientCustomizer(builder -> builder.register(viaDefault))
            .build();
    try (DefaultKeycloakConnectionManager connectionManager =
        new DefaultKeycloakConnectionManager(config)) {
      connectionManager.realmManager("master").getRoles();
    }

    try (KeycloakAdminCliConnectionManager adminCli =
        new KeycloakAdminCliConnectionManager(
            serverUrl(),
            new KeycloakAdminCredentialsConfig(ADMIN_USERNAME, ADMIN_PASSWORD),
            ManagedNaming.standard(),
            builder -> builder.register(viaAdminCli))) {
      assertThat(adminCli.getKeycloakClient()).isNotNull();
    }

    // The default connection above bootstrapped the client, so this one can authenticate as it.
    try (KeycloakProvisioningConnectionManager direct =
        new KeycloakProvisioningConnectionManager(
            serverUrl(),
            CLIENT_CONFIG,
            ManagedNaming.standard(),
            builder -> builder.register(viaProvisioningClient))) {
      direct.realmManager("master").getRoles();
    }

    assertThat(viaDefault.seenRequests()).as("default connection").isNotEmpty();
    assertThat(viaAdminCli.seenRequests()).as("admin-cli").isNotEmpty();
    assertThat(viaProvisioningClient.seenRequests()).as("provisioning client").isNotEmpty();
  }

  private static final class RecordingFilter implements ClientRequestFilter {

    private final List<String> seenRequests = new CopyOnWriteArrayList<>();

    @Override
    public void filter(ClientRequestContext requestContext) {
      seenRequests.add(requestContext.getMethod() + " " + requestContext.getUri().getPath());
    }

    List<String> seenRequests() {
      return seenRequests;
    }
  }
}
