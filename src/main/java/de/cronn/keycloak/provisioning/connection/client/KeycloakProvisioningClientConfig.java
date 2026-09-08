package de.cronn.keycloak.provisioning.connection.client;

import de.cronn.keycloak.provisioning.SystemClientId;
import java.util.Objects;
import org.apache.commons.lang3.Validate;

public record KeycloakProvisioningClientConfig(
    SystemClientId clientId, String secret, String name, String description) {

  public KeycloakProvisioningClientConfig {
    Objects.requireNonNull(clientId);
    Validate.notBlank(secret);
  }

  public KeycloakProvisioningClientConfig(SystemClientId clientId, String secret) {
    this(
        clientId,
        secret,
        "keycloak-provisioning-client",
        "Used for provisioning and managing this keycloak instance.");
  }
}
