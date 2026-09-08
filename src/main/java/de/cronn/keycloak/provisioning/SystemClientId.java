package de.cronn.keycloak.provisioning;

import org.apache.commons.lang3.Validate;

/**
 * A client managed by this library, in both the configured and the Keycloak-facing form. Created
 * via {@link SystemClientNaming#clientId(String)}, which owns the prefix.
 */
public record SystemClientId(String clientId, String value) {

  public SystemClientId {
    Validate.notBlank(clientId);
    Validate.notBlank(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
