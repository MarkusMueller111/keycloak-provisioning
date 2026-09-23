package de.cronn.keycloak.provisioning;

/** User-profile attributes Keycloak declares in every realm itself. */
public enum KeycloakProvidedUserAttribute {
  USERNAME("username", false),
  EMAIL("email", false),
  FIRST_NAME("firstName", true),
  LAST_NAME("lastName", true),
  ;

  private final String attributeName;
  private final boolean removable;

  KeycloakProvidedUserAttribute(String attributeName, boolean removable) {
    this.attributeName = attributeName;
    this.removable = removable;
  }

  public String getAttributeName() {
    return attributeName;
  }

  /** Keycloak answers a user profile without {@code username} or {@code email} with HTTP 400. */
  public boolean isRemovable() {
    return removable;
  }
}
