package de.cronn.keycloak.provisioning;

import de.cronn.keycloak.provisioning.connection.admincli.KeycloakAdminCliConnectionManager;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.keycloak.admin.client.Keycloak;
import org.testcontainers.containers.GenericContainer;

/**
 * For tests that change the master realm irreversibly — hardening it, or observing the state before
 * the provisioning client exists. Each test gets a container of its own, which is slow but keeps
 * them independent of each other and of execution order.
 */
abstract class OwnKeycloakTest extends KeycloakContainerSupport {

  private GenericContainer<?> keycloak;
  private KeycloakAdminCliConnectionManager adminConnection;

  @Override
  protected GenericContainer<?> keycloak() {
    return keycloak;
  }

  @Override
  protected Keycloak adminClient() {
    return adminConnection.getKeycloakClient();
  }

  /** Extra container environment, for a bootstrap form the default container does not set up. */
  protected Map<String, String> extraEnvironment() {
    return Map.of();
  }

  @BeforeEach
  void startOwnKeycloak() {
    keycloak = startKeycloak(extraEnvironment());
    adminConnection = adminConnection(keycloak);
  }

  @AfterEach
  void stopOwnKeycloak() {
    adminConnection.close();
    keycloak.stop();
  }
}
