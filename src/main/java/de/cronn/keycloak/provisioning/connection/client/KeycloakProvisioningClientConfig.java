package de.cronn.keycloak.provisioning.connection.client;

import java.util.Objects;
import org.apache.commons.lang3.Validate;

/**
 * The client this library authenticates as. Its id deliberately carries no managed prefix: it is
 * created once at bootstrap and is not part of any realm's desired state, so keeping it outside the
 * managed namespace is what stops a consumer's own client reconciliation from deleting it.
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
