package de.cronn.keycloak.provisioning.connection;

import de.cronn.keycloak.provisioning.KeycloakException;
import de.cronn.keycloak.provisioning.RealmManager;
import de.cronn.keycloak.provisioning.connection.admincli.KeycloakAdminCliConnectionManager;
import de.cronn.keycloak.provisioning.connection.client.KeycloakProvisioningConnectionManager;
import de.cronn.keycloak.provisioning.connection.client.ProvisioningClientBootstrapper;
import jakarta.ws.rs.NotAuthorizedException;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.keycloak.admin.client.Keycloak;

public final class DefaultKeycloakConnectionManager extends KeycloakConnectionManager {

  private KeycloakProvisioningConnectionManager keycloakProvisioningConnectionManager;

  public DefaultKeycloakConnectionManager(KeycloakDefaultConnectionConfig config) {
    super(config.clientNaming());
    try {
      connect(config);
    } catch (RuntimeException e) {
      close();
      throw new KeycloakException(bootstrapFailureMessage(config), e);
    }
  }

  private void connect(KeycloakDefaultConnectionConfig config) {
    try {
      this.keycloakProvisioningConnectionManager =
          new KeycloakProvisioningConnectionManager(
              config.serverUrl(), config.provisioningClientConfig(), config.clientNaming());
      bootstrapProvisioningClient(this.keycloakProvisioningConnectionManager, config);
    } catch (RuntimeException e) {
      close();
      if (ExceptionUtils.indexOfType(e, NotAuthorizedException.class) == -1) {
        throw e;
      }
      log.info(
          "Failed to connect using provisioning client. Bootstrapping provisioning client using admin-cli client");
      try (KeycloakAdminCliConnectionManager keycloakAdminCliClientManager =
          new KeycloakAdminCliConnectionManager(
              config.serverUrl(), config.adminCredentialsConfig(), config.clientNaming())) {
        bootstrapProvisioningClient(keycloakAdminCliClientManager, config);
        this.keycloakProvisioningConnectionManager =
            new KeycloakProvisioningConnectionManager(
                config.serverUrl(), config.provisioningClientConfig(), config.clientNaming());
      }
    }
  }

  private static String bootstrapFailureMessage(KeycloakDefaultConnectionConfig config) {
    return "Failed to bootstrap provisioning client '"
        + config.provisioningClientConfig().clientId()
        + "' at "
        + config.serverUrl();
  }

  private static void bootstrapProvisioningClient(
      KeycloakConnectionManager keycloakConnectionManager, KeycloakDefaultConnectionConfig config) {
    RealmManager realmManager = keycloakConnectionManager.realmManager("master");
    ProvisioningClientBootstrapper.registerClient(realmManager, config.provisioningClientConfig());
  }

  @Override
  public Keycloak getKeycloakClient() {
    return this.keycloakProvisioningConnectionManager.getKeycloakClient();
  }

  @Override
  public void close() {
    if (keycloakProvisioningConnectionManager != null) {
      keycloakProvisioningConnectionManager.close();
      keycloakProvisioningConnectionManager = null;
    }
  }
}
