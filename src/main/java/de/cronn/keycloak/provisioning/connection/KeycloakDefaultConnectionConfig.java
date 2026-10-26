package de.cronn.keycloak.provisioning.connection;

import de.cronn.keycloak.provisioning.ManagedNaming;
import de.cronn.keycloak.provisioning.connection.admincli.KeycloakAdminCredentialsConfig;
import de.cronn.keycloak.provisioning.connection.client.KeycloakProvisioningClientConfig;
import de.cronn.keycloak.provisioning.connection.client.ProvisioningClientBootstrapper;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.commons.lang3.Validate;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;

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
  private final Consumer<ResteasyClientBuilder> httpClientCustomizer;

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
    this(
        serverUrl,
        adminCredentialsConfig,
        provisioningClientConfig,
        clientNaming,
        httpClient -> {});
  }

  private KeycloakDefaultConnectionConfig(
      String serverUrl,
      KeycloakAdminCredentialsConfig adminCredentialsConfig,
      KeycloakProvisioningClientConfig provisioningClientConfig,
      ManagedNaming clientNaming,
      Consumer<ResteasyClientBuilder> httpClientCustomizer) {
    this.serverUrl = Validate.notBlank(serverUrl);
    this.adminCredentialsConfig = Objects.requireNonNull(adminCredentialsConfig);
    this.provisioningClientConfig = Objects.requireNonNull(provisioningClientConfig);
    this.clientNaming =
        Objects.requireNonNull(clientNaming)
            .withProvisioningClient(provisioningClientConfig.clientId());
    this.httpClientCustomizer = Objects.requireNonNull(httpClientCustomizer);
  }

  public static Builder builder(
      String serverUrl,
      KeycloakAdminCredentialsConfig adminCredentialsConfig,
      KeycloakProvisioningClientConfig provisioningClientConfig) {
    return new Builder(serverUrl, adminCredentialsConfig, provisioningClientConfig);
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

  public Consumer<ResteasyClientBuilder> httpClientCustomizer() {
    return httpClientCustomizer;
  }

  public static final class Builder {

    private final String serverUrl;
    private final KeycloakAdminCredentialsConfig adminCredentialsConfig;
    private final KeycloakProvisioningClientConfig provisioningClientConfig;
    private ManagedNaming clientNaming = ManagedNaming.standard();
    private Consumer<ResteasyClientBuilder> httpClientCustomizer = httpClient -> {};

    private Builder(
        String serverUrl,
        KeycloakAdminCredentialsConfig adminCredentialsConfig,
        KeycloakProvisioningClientConfig provisioningClientConfig) {
      this.serverUrl = serverUrl;
      this.adminCredentialsConfig = adminCredentialsConfig;
      this.provisioningClientConfig = provisioningClientConfig;
    }

    public Builder clientNaming(ManagedNaming clientNaming) {
      this.clientNaming = clientNaming;
      return this;
    }

    /**
     * Runs against the HTTP client of every connection, after this library's own registrations
     * (timeouts, request logging), so it can add further ones such as a {@code
     * ClientRequestFilter}.
     */
    public Builder httpClientCustomizer(Consumer<ResteasyClientBuilder> httpClientCustomizer) {
      this.httpClientCustomizer = httpClientCustomizer;
      return this;
    }

    public KeycloakDefaultConnectionConfig build() {
      return new KeycloakDefaultConnectionConfig(
          serverUrl,
          adminCredentialsConfig,
          provisioningClientConfig,
          clientNaming,
          httpClientCustomizer);
    }
  }
}
