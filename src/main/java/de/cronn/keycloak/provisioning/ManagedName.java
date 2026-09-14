package de.cronn.keycloak.provisioning;

import org.apache.commons.lang3.Validate;

/**
 * The name of an object managed by this library, in both the configured and the Keycloak-facing
 * form. Created via {@link ManagedNaming}, which owns the prefix.
 */
public record ManagedName(String name, String value) {

  public ManagedName {
    Validate.notBlank(name);
    Validate.notBlank(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
