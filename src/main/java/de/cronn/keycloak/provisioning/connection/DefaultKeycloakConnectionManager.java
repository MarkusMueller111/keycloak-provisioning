package de.cronn.keycloak.provisioning.connection;

import de.cronn.keycloak.provisioning.KeycloakException;
import de.cronn.keycloak.provisioning.RealmManager;
import de.cronn.keycloak.provisioning.connection.admincli.KeycloakAdminCliConnectionManager;
import de.cronn.keycloak.provisioning.connection.client.KeycloakProvisioningConnectionManager;
import de.cronn.keycloak.provisioning.connection.client.ProvisioningClientBootstrapper;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.keycloak.admin.client.Keycloak;

public final class DefaultKeycloakConnectionManager extends KeycloakConnectionManager {

  private static final String MISSING_DPOP_PROOF = "DPoP proof is missing";

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
              config.serverUrl(),
              config.provisioningClientConfig(),
              config.clientNaming(),
              config.httpClientCustomizer());
      bootstrapProvisioningClient(this.keycloakProvisioningConnectionManager, config);
    } catch (RuntimeException e) {
      close();
      if (!isRecoverableByBootstrapping(e, config.provisioningClientConfig().useDPoP())) {
        throw e;
      }
      log.info(
          "Failed to connect using provisioning client. Bootstrapping provisioning client using admin-cli client");
      try (KeycloakAdminCliConnectionManager keycloakAdminCliClientManager =
          new KeycloakAdminCliConnectionManager(
              config.serverUrl(),
              config.adminCredentialsConfig(),
              config.clientNaming(),
              config.httpClientCustomizer())) {
        bootstrapProvisioningClient(keycloakAdminCliClientManager, config);
        this.keycloakProvisioningConnectionManager =
            new KeycloakProvisioningConnectionManager(
                config.serverUrl(),
                config.provisioningClientConfig(),
                config.clientNaming(),
                config.httpClientCustomizer());
      }
    }
  }

  /**
   * 401 when the client cannot authenticate at all, 403 when it can but lost its admin role -
   * granting that role back is what bootstrapping does, so both are worth the fallback. So is
   * Keycloak demanding a DPoP proof that this connection does not send, which bootstrapping lifts
   * by registering the client without the requirement. Any other 400 is not.
   */
  static boolean isRecoverableByBootstrapping(RuntimeException e, boolean useDPoP) {
    return ExceptionUtils.indexOfType(e, NotAuthorizedException.class) != -1
        || ExceptionUtils.indexOfType(e, ForbiddenException.class) != -1
        || (!useDPoP && isMissingDPoPProof(e));
  }

  private static boolean isMissingDPoPProof(RuntimeException e) {
    int index = ExceptionUtils.indexOfType(e, BadRequestException.class);
    if (index == -1) {
      return false;
    }
    BadRequestException badRequest =
        (BadRequestException) ExceptionUtils.getThrowableList(e).get(index);
    try {
      String body = badRequest.getResponse().readEntity(String.class);
      return body.contains("\"invalid_request\"") && body.contains(MISSING_DPOP_PROOF);
    } catch (RuntimeException unreadable) {
      return false;
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
