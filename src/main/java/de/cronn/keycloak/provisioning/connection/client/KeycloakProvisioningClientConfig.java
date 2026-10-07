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
    String clientId, String secret, String name, String description) {

  public KeycloakProvisioningClientConfig {
    Validate.notBlank(clientId);
    Validate.notBlank(secret);
    Objects.requireNonNull(name);
  }

  public KeycloakProvisioningClientConfig(String clientId, String secret) {
    this(
        clientId,
        secret,
        "keycloak-provisioning-client",
        "Used for provisioning and managing this keycloak instance.");
  }
}
