package de.cronn.keycloak.provisioning.connection;

import de.cronn.keycloak.provisioning.KeycloakException;
import de.cronn.keycloak.provisioning.ManagedNaming;
import de.cronn.keycloak.provisioning.RealmManager;
import jakarta.ws.rs.client.Client;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.internal.ResteasyClientBuilderImpl;
import org.keycloak.admin.client.JacksonProvider;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class KeycloakConnectionManager implements AutoCloseable {
  protected static final Duration TIMEOUT = Duration.ofSeconds(20);
  protected final Logger log = LoggerFactory.getLogger(getClass());

  private final ManagedNaming clientNaming;

  protected KeycloakConnectionManager() {
    this(ManagedNaming.standard());
  }

  protected KeycloakConnectionManager(ManagedNaming clientNaming) {
    this.clientNaming = Objects.requireNonNull(clientNaming);
    log.info("Connecting to Keycloak using {}", getClass().getSimpleName());
  }

  /** Every realm reached through this connection is managed with the same naming. */
  public RealmManager realmManager(String realmName) {
    return new RealmManager(getKeycloakClient(), realmName, clientNaming);
  }

  /**
   * Acquires a token so a broken connection fails here rather than on some later call: the Keycloak
   * builder only constructs, it never contacts the server. Subclasses must call this once their
   * client is assigned.
   */
  protected final void connect() {
    try {
      getKeycloakClient().tokenManager().getAccessToken();
    } catch (RuntimeException e) {
      close();
      throw new KeycloakException(
          "Failed to connect to Keycloak using " + getClass().getSimpleName(), e);
    }
  }

  /**
   * {@code httpClientCustomizer} runs after this library's own registrations (timeouts, request
   * logging), so it can add further ones, such as a {@code ClientRequestFilter} to propagate
   * tracing.
   */
  protected static KeycloakBuilder getDefaultKeycloakBuilder(
      String serverUrl, Consumer<ResteasyClientBuilder> httpClientCustomizer) {
    return KeycloakBuilder.builder()
        .resteasyClient(createClientWithTimeout(httpClientCustomizer))
        .serverUrl(serverUrl)
        .realm("master");
  }

  private static Client createClientWithTimeout(
      Consumer<ResteasyClientBuilder> httpClientCustomizer) {
    ResteasyClientBuilderImpl clientBuilder =
        new ResteasyClientBuilderImpl()
            .connectTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            // Keycloak does use the JacksonProvider when initializing the default rest easy
            // client, so we will too
            // see: /org/keycloak/admin/client/spi/ResteasyClientClassicProvider.java:38
            .register(JacksonProvider.class, 100)
            .register(new KeycloakRequestLogger())
            .register(new KeycloakResponseLogger());
    httpClientCustomizer.accept(clientBuilder);
    return clientBuilder.build();
  }

  public abstract Keycloak getKeycloakClient();

  @Override
  public abstract void close();
}
