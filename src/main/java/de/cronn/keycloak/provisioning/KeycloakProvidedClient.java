package de.cronn.keycloak.provisioning;

import java.util.List;

/** Clients Keycloak creates in every realm itself. */
public enum KeycloakProvidedClient {
  ACCOUNT("account"),
  ACCOUNT_CONSOLE("account-console"),
  ADMIN_CLI("admin-cli"),
  BROKER("broker"),
  REALM_MANAGEMENT("realm-management"),
  SECURITY_ADMIN_CONSOLE("security-admin-console"),
  ;

  /** Ordered, since it ends up in a LinkedHashSet that reaches log output. */
  public static final List<KeycloakProvidedClient> DISABLED_BY_DEFAULT = List.of(BROKER);

  private final String clientId;

  KeycloakProvidedClient(String clientId) {
    this.clientId = clientId;
  }

  public String getClientId() {
    return clientId;
  }
}
