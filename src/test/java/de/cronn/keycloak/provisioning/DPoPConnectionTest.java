package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import de.cronn.keycloak.provisioning.connection.DefaultKeycloakConnectionManager;
import de.cronn.keycloak.provisioning.connection.KeycloakDefaultConnectionConfig;
import de.cronn.keycloak.provisioning.connection.admincli.KeycloakAdminCredentialsConfig;
import de.cronn.keycloak.provisioning.connection.client.KeycloakProvisioningClientConfig;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Connects to one container three times, as the same provisioning client, to cover switching DPoP
 * on and off again. Kept on a container of its own because it changes how the master realm's
 * provisioning client authenticates.
 */
class DPoPConnectionTest extends OwnKeycloakTest {

  private static final String DPOP_ATTRIBUTE = "dpop.bound.access.tokens";

  @Test
  void requestsDPoPBoundTokensOnlyWhileEnabled() throws JsonProcessingException {
    assertConnectionUsesDPoP(false);
    assertConnectionUsesDPoP(true);
    assertConnectionUsesDPoP(false);
  }

  private void assertConnectionUsesDPoP(boolean useDPoP) throws JsonProcessingException {
    KeycloakProvisioningClientConfig config =
        KeycloakProvisioningClientConfig.builder("dpop-client", "secret").useDPoP(useDPoP).build();

    try (DefaultKeycloakConnectionManager connectionManager =
        new DefaultKeycloakConnectionManager(
            new KeycloakDefaultConnectionConfig(
                serverUrl(),
                new KeycloakAdminCredentialsConfig(ADMIN_USERNAME, ADMIN_PASSWORD),
                config))) {
      String accessToken =
          connectionManager.getKeycloakClient().tokenManager().getAccessTokenString();
      assertThat(confirmationClaim(accessToken)).as("cnf claim").isEqualTo(useDPoP);

      Map<String, String> attributes =
          connectionManager
              .realmManager("master")
              .getClientByClientId(config.clientId())
              .orElseThrow()
              .toRepresentation()
              .getAttributes();
      assertThat(attributes.get(DPOP_ATTRIBUTE))
          .as(DPOP_ATTRIBUTE)
          .isEqualTo(useDPoP ? "true" : null);
    }
  }

  /** Whether Keycloak added the {@code cnf} claim it puts on a DPoP-bound access token. */
  private boolean confirmationClaim(String accessToken) throws JsonProcessingException {
    String payload =
        new String(
            Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]), StandardCharsets.UTF_8);
    return jsonMapper().readTree(payload).get("cnf") != null;
  }
}
