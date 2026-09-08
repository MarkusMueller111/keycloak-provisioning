package de.cronn.keycloak.provisioning.connection.admincli;

import org.apache.commons.lang3.Validate;

public record KeycloakAdminCredentialsConfig(String adminUsername, String adminPassword) {

  public KeycloakAdminCredentialsConfig {
    Validate.notBlank(adminUsername);
    Validate.notBlank(adminPassword);
  }
}
