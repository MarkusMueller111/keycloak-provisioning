package de.cronn.keycloak.provisioning.connection.client;

import de.cronn.keycloak.provisioning.ManagedNaming;
import de.cronn.keycloak.provisioning.connection.KeycloakConnectionManager;
import java.util.function.Consumer;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.common.crypto.CryptoIntegration;

public final class KeycloakProvisioningConnectionManager extends KeycloakConnectionManager {

  private final Keycloak keycloak;

  public KeycloakProvisioningConnectionManager(
      String serverUrl, KeycloakProvisioningClientConfig config) {
    this(serverUrl, config, ManagedNaming.standard());
  }

  public KeycloakProvisioningConnectionManager(
      String serverUrl, KeycloakProvisioningClientConfig config, ManagedNaming clientNaming) {
    this(serverUrl, config, clientNaming, httpClient -> {});
  }

  public KeycloakProvisioningConnectionManager(
      String serverUrl,
      KeycloakProvisioningClientConfig config,
      ManagedNaming clientNaming,
      Consumer<ResteasyClientBuilder> httpClientCustomizer) {
    super(clientNaming.withProvisioningClient(config.clientId()));
    if (config.useDPoP()) {
      // The DPoP proof needs a key pair, generated through a crypto provider that a plain JVM does
      // not have; a consumer that enables DPoP must put one (e.g. keycloak-crypto-default) on its
      // runtime classpath. See https://github.com/keycloak/keycloak/issues/27916.
      CryptoIntegration.init(KeycloakProvisioningConnectionManager.class.getClassLoader());
    }
    this.keycloak =
        getDefaultKeycloakBuilder(serverUrl, httpClientCustomizer)
            .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
            .clientId(config.clientId())
            .clientSecret(config.secret())
            .useDPoP(config.useDPoP())
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
