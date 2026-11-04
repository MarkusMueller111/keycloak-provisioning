package de.cronn.keycloak.provisioning;

import java.util.Arrays;

/** Client scopes Keycloak creates in every realm itself. */
public enum KeycloakProvidedClientScope {
  ACR("acr"),
  ADDRESS("address"),
  AUTHN_CONTEXT_CLASS_REF("AuthnContextClassRef"),
  BASIC("basic"),
  EMAIL("email"),
  MICROPROFILE_JWT("microprofile-jwt"),
  OFFLINE_ACCESS("offline_access"),
  ORGANIZATION("organization"),
  PHONE("phone"),
  PROFILE("profile"),
  ROLE_LIST("role_list"),
  ROLES("roles"),
  SAML_ORGANIZATION("saml_organization"),
  SERVICE_ACCOUNT("service_account"),
  WEB_ORIGINS("web-origins"),
  ;

  private final String scopeName;

  KeycloakProvidedClientScope(String scopeName) {
    this.scopeName = scopeName;
  }

  public String getScopeName() {
    return scopeName;
  }

  public static boolean isKeycloakProvided(String scopeName) {
    return Arrays.stream(values()).anyMatch(scope -> scope.scopeName.equals(scopeName));
  }
}
