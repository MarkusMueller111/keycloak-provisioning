package de.cronn.keycloak.provisioning.connection.admincli;

import de.cronn.keycloak.provisioning.KeycloakProvidedClient;
import de.cronn.keycloak.provisioning.ManagedNaming;
import de.cronn.keycloak.provisioning.connection.KeycloakConnectionManager;
import java.util.function.Consumer;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;

public final class KeycloakAdminCliConnectionManager extends KeycloakConnectionManager {

  private final Keycloak keycloak;

  public KeycloakAdminCliConnectionManager(
      String serverUrl, KeycloakAdminCredentialsConfig config) {
    this(serverUrl, config, ManagedNaming.standard());
  }

  public KeycloakAdminCliConnectionManager(
      String serverUrl, KeycloakAdminCredentialsConfig config, ManagedNaming clientNaming) {
    this(serverUrl, config, clientNaming, httpClient -> {});
  }

  public KeycloakAdminCliConnectionManager(
      String serverUrl,
      KeycloakAdminCredentialsConfig config,
      ManagedNaming clientNaming,
      Consumer<ResteasyClientBuilder> httpClientCustomizer) {
    super(clientNaming);
    this.keycloak =
        getDefaultKeycloakBuilder(serverUrl, httpClientCustomizer)
            .grantType(OAuth2Constants.PASSWORD)
            .clientId(KeycloakProvidedClient.ADMIN_CLI.getClientId())
            .username(config.adminUsername())
            .password(config.adminPassword())
            .build();
    connect();
  }

  @Override
  public Keycloak getKeycloakClient() {
    return this.keycloak;
  }

  @Override
  public void close() {
    this.keycloak.close();
  }
}
