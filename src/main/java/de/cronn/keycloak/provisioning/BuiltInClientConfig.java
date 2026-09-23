package de.cronn.keycloak.provisioning;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.keycloak.representations.idm.ClientRepresentation;

/**
 * How one of Keycloak's own clients should be configured. These clients are never created or
 * deleted, only adjusted: a client without an entry is reconciled to enabled and otherwise left as
 * Keycloak made it.
 *
 * <p>{@link Builder#enabled(boolean)} is written on every run, so dropping an entry re-enables its
 * client. A {@link Builder#customizer(Consumer)} is applied as a delta and is <em>not</em> reverted
 * when removed — what it changed stays changed, since there is no way to know what Keycloak's value
 * for that field would have been. Set the opposite explicitly to undo one.
 */
public record BuiltInClientConfig(
    KeycloakProvidedClient client, boolean enabled, Consumer<ClientRepresentation> customizer) {

  public static final List<BuiltInClientConfig> BUILT_IN_CLIENT_DEFAULTS =
      List.of(defaultBrokerBuilder().build(), defaultAccountBuilder().build());

  public BuiltInClientConfig {
    Objects.requireNonNull(client, "client must not be null");
    customizer = customizer == null ? clientRepresentation -> {} : customizer;
  }

  public static Builder forClient(KeycloakProvidedClient client) {
    return new Builder(client);
  }

  /** Disabled: it only matters for identity brokering, which most realms do not use. */
  public static Builder defaultBrokerBuilder() {
    return forClient(KeycloakProvidedClient.BROKER).enabled(false);
  }

  /** The account console needs it enabled, but reaches it through {@code account-console}. */
  public static Builder defaultAccountBuilder() {
    return forClient(KeycloakProvidedClient.ACCOUNT)
        .customizer(client -> client.setStandardFlowEnabled(false));
  }

  public void applyTo(ClientRepresentation clientRepresentation) {
    clientRepresentation.setEnabled(enabled);
    customizer.accept(clientRepresentation);
  }

  public static final class Builder {

    private final KeycloakProvidedClient client;
    private boolean enabled = true;
    private Consumer<ClientRepresentation> customizer;

    private Builder(KeycloakProvidedClient client) {
      this.client = Objects.requireNonNull(client, "client must not be null");
    }

    public Builder enabled(boolean enabled) {
      this.enabled = enabled;
      return this;
    }

    /**
     * Applied after {@link #enabled(boolean)}, so it can override it. Sticky: removing the
     * customizer later does not restore what it changed.
     */
    public Builder customizer(Consumer<ClientRepresentation> customizer) {
      this.customizer = Objects.requireNonNull(customizer, "customizer must not be null");
      return this;
    }

    public BuiltInClientConfig build() {
      return new BuiltInClientConfig(client, enabled, customizer);
    }
  }
}
