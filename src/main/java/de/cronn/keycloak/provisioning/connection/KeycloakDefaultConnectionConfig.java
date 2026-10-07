package de.cronn.keycloak.provisioning.connection;

import de.cronn.keycloak.provisioning.ManagedNaming;
import de.cronn.keycloak.provisioning.connection.admincli.KeycloakAdminCredentialsConfig;
import de.cronn.keycloak.provisioning.connection.client.KeycloakProvisioningClientConfig;
import de.cronn.keycloak.provisioning.connection.client.ProvisioningClientBootstrapper;
import java.util.Objects;
import org.apache.commons.lang3.Validate;

/**
 * Everything needed to connect to a Keycloak server's <em>master</em> realm, either as the
 * bootstrap admin user or as the provisioning client managed by {@link
 * ProvisioningClientBootstrapper}. Handed to {@link DefaultKeycloakConnectionManager}, which
 * decides which connection to use and when to (re-)bootstrap the provisioning client.
 */
public final class KeycloakDefaultConnectionConfig {

  private final String serverUrl;
  private final KeycloakAdminCredentialsConfig adminCredentialsConfig;
  private final KeycloakProvisioningClientConfig provisioningClientConfig;
  private final ManagedNaming clientNaming;

  public KeycloakDefaultConnectionConfig(
      String serverUrl,
      KeycloakAdminCredentialsConfig adminCredentialsConfig,
      KeycloakProvisioningClientConfig provisioningClientConfig) {
    this(serverUrl, adminCredentialsConfig, provisioningClientConfig, ManagedNaming.standard());
  }

  public KeycloakDefaultConnectionConfig(
      String serverUrl,
      KeycloakAdminCredentialsConfig adminCredentialsConfig,
      KeycloakProvisioningClientConfig provisioningClientConfig,
      ManagedNaming clientNaming) {
    this.serverUrl = Validate.notBlank(serverUrl);
    this.adminCredentialsConfig = Objects.requireNonNull(adminCredentialsConfig);
    this.provisioningClientConfig = Objects.requireNonNull(provisioningClientConfig);
    this.clientNaming =
        Objects.requireNonNull(clientNaming)
            .withProvisioningClient(provisioningClientConfig.clientId());
  }

  public ManagedNaming clientNaming() {
    return clientNaming;
  }

  public String serverUrl() {
    return serverUrl;
  }

  public KeycloakProvisioningClientConfig provisioningClientConfig() {
    return provisioningClientConfig;
  }

  public KeycloakAdminCredentialsConfig adminCredentialsConfig() {
    return adminCredentialsConfig;
  }
}
