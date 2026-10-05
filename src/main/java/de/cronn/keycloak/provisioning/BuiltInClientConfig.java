package de.cronn.keycloak.provisioning;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.apache.commons.lang3.Validate;
import org.keycloak.representations.idm.ClientRepresentation;

/**
 * How one of Keycloak's own clients should be configured. These clients are never created or
 * deleted, only adjusted: a client without an entry falls back to {@link #BUILT_IN_CLIENT_DEFAULTS}
 * and is otherwise left as Keycloak made it.
 *
 * <p>{@link Builder#enabled(boolean)} is written on every run, so dropping an entry restores that
 * default — enabled for most, disabled for {@code broker} and {@code admin-cli}. A {@link
 * Builder#customizer(Consumer)} is applied as a delta and is <em>not</em> reverted when removed —
 * what it changed stays changed, since there is no way to know what Keycloak's value for that field
 * would have been. Set the opposite explicitly to undo one.
 */
public record BuiltInClientConfig(
    KeycloakProvidedClient client, boolean enabled, Consumer<ClientRepresentation> customizer) {

  public static final List<BuiltInClientConfig> BUILT_IN_CLIENT_DEFAULTS =
      List.of(
          defaultBrokerBuilder().build(),
          defaultAccountBuilder().build(),
          defaultAdminCliBuilder().build());

  public BuiltInClientConfig {
    Objects.requireNonNull(client, "client must not be null");
    customizer = customizer == null ? clientRepresentation -> {} : customizer;
  }

  /** The declared clients over {@link #BUILT_IN_CLIENT_DEFAULTS}, one entry per client. */
  public static List<BuiltInClientConfig> mergeOverDefaults(List<BuiltInClientConfig> configured) {
    if (configured != null) {
      validate(configured);
    }
    Map<KeycloakProvidedClient, BuiltInClientConfig> merged = new LinkedHashMap<>();
    BUILT_IN_CLIENT_DEFAULTS.forEach(config -> merged.put(config.client(), config));
    if (configured != null) {
      configured.forEach(config -> merged.put(config.client(), config));
    }
    return List.copyOf(merged.values());
  }

  /**
   * Rejects a client declared twice, and a disabled {@code realm-management}, which Keycloak
   * answers with a 403 mid-provisioning.
   */
  static void validate(Collection<BuiltInClientConfig> configured) {
    Set<KeycloakProvidedClient> seen = new LinkedHashSet<>();
    for (BuiltInClientConfig config : configured) {
      Validate.isTrue(
          seen.add(config.client()),
          "Client '%s' is configured more than once",
          config.client().getClientId());
      Validate.isTrue(
          config.client() != KeycloakProvidedClient.REALM_MANAGEMENT || config.enabled(),
          "Client '%s' cannot be disabled: it backs the realm's admin permissions",
          config.client().getClientId());
    }
  }

  public static Builder forClient(KeycloakProvidedClient client) {
    return new Builder(client);
  }

  /** Disabled: it only matters for identity brokering, which most realms do not use. */
  public static Builder defaultBrokerBuilder() {
    return forClient(KeycloakProvidedClient.BROKER).enabled(false);
  }

  /**
   * Disabled: it enables the direct access grant, i.e. a password grant that bypasses the browser
   * flow including any 2FA configured there.
   *
   * <p>Declare it {@code enabled(true)} when provisioning connects via {@code
   * KeycloakAdminCliConnectionManager} and relies on {@code admin-cli} as a fallback. Disabling it
   * does not cost you the admin console, which authenticates through {@code
   * security-admin-console}.
   */
  public static Builder defaultAdminCliBuilder() {
    return forClient(KeycloakProvidedClient.ADMIN_CLI).enabled(false);
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
