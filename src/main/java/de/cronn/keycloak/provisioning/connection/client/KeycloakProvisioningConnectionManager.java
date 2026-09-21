package de.cronn.keycloak.provisioning.connection.client;

import de.cronn.keycloak.provisioning.SystemClientNaming;
import de.cronn.keycloak.provisioning.connection.KeycloakConnectionManager;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;

public final class KeycloakProvisioningConnectionManager extends KeycloakConnectionManager {

  private final Keycloak keycloak;

  public KeycloakProvisioningConnectionManager(
      String serverUrl, KeycloakProvisioningClientConfig config) {
    this(serverUrl, config, SystemClientNaming.standard());
  }

  public KeycloakProvisioningConnectionManager(
      String serverUrl, KeycloakProvisioningClientConfig config, SystemClientNaming clientNaming) {
    super(clientNaming);
    this.keycloak =
        getDefaultKeycloakBuilder(serverUrl)
            .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
            .clientId(config.clientId())
            .clientSecret(config.secret())
            .build();
    connect();
  }

  @Override
  public Keycloak getKeycloakClient() {
    return keycloak;
  }

  @Override
  public void close() {
    this.keycloak.close();
  }
}
