package de.cronn.keycloak.provisioning;

/** When Keycloak demands HTTPS, as accepted by {@code RealmRepresentation#setSslRequired}. */
public enum SslRequired {
  ALL("all"),
  EXTERNAL("external"),
  NONE("none");

  private final String keycloakValue;

  SslRequired(String keycloakValue) {
    this.keycloakValue = keycloakValue;
  }

  public String getKeycloakValue() {
    return keycloakValue;
  }
}
