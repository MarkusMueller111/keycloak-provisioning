package de.cronn.keycloak.provisioning;

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
  private final String clientId;

  KeycloakProvidedClient(String clientId) {
    this.clientId = clientId;
  }

  public String getClientId() {
    return clientId;
  }
}
