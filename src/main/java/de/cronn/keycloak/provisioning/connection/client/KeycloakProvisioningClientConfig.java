package de.cronn.keycloak.provisioning.connection.client;

import java.util.Objects;
import org.apache.commons.lang3.Validate;

/**
 * The client this library authenticates as. It is created once at bootstrap and is not part of any
 * realm's desired state, so provisioning never deletes it: the connection exempts this id from the
 * deletion fence, which leaves a consumer free to name it by its own convention even when that
 * convention carries the managed prefix.
 */
public record KeycloakProvisioningClientConfig(
    String clientId, String secret, String name, String description, boolean useDPoP) {

  private static final String DEFAULT_NAME = "keycloak-provisioning-client";
  private static final String DEFAULT_DESCRIPTION =
      "Used for provisioning and managing this keycloak instance.";

  public KeycloakProvisioningClientConfig {
    Validate.notBlank(clientId);
    Validate.notBlank(secret);
    Objects.requireNonNull(name);
  }

  public KeycloakProvisioningClientConfig(
      String clientId, String secret, String name, String description) {
    this(clientId, secret, name, description, false);
  }

  public KeycloakProvisioningClientConfig(String clientId, String secret) {
    this(clientId, secret, DEFAULT_NAME, DEFAULT_DESCRIPTION, false);
  }

  /**
   * {@code clientId} and {@code secret} are required; everything else has a default.
   *
   * @see Builder#useDPoP(boolean)
   */
  public static Builder builder(String clientId, String secret) {
    return new Builder(clientId, secret);
  }

  public static final class Builder {

    private final String clientId;
    private final String secret;
    private String name = DEFAULT_NAME;
    private String description = DEFAULT_DESCRIPTION;
    private boolean useDPoP;

    private Builder(String clientId, String secret) {
      this.clientId = clientId;
      this.secret = secret;
    }

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder description(String description) {
      this.description = description;
      return this;
    }

    /**
     * Requests DPoP-bound tokens for the provisioning client's own connection and registers the
     * client with {@code dpop.bound.access.tokens=true}, so a stolen token cannot be replayed
     * without its key. Off by default.
     */
    public Builder useDPoP(boolean useDPoP) {
      this.useDPoP = useDPoP;
      return this;
    }

    public KeycloakProvisioningClientConfig build() {
      return new KeycloakProvisioningClientConfig(clientId, secret, name, description, useDPoP);
    }
  }
}
