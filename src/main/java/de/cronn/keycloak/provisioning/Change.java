package de.cronn.keycloak.provisioning;

/**
 * One write a provisioning run sent to Keycloak.
 *
 * @param name the changed object's identifier: client id, scope or flow alias, username, ...
 * @param parent the client, client scope or flow the object belongs to, or {@code null}
 * @param diff the masked JSON diff of an {@link Type#UPDATED} object, otherwise {@code null}
 */
public record Change(Target target, String name, String parent, Type type, String diff) {

  public enum Type {
    ADDED,
    UPDATED,
    REMOVED
  }

  public enum Target {
    REALM("realm"),
    CLIENT("client"),
    BUILT_IN_CLIENT("built-in client"),
    CLIENT_SCOPE("client scope"),
    DEFAULT_CLIENT_SCOPE("default client scope"),
    OPTIONAL_CLIENT_SCOPE("optional client scope"),
    PROTOCOL_MAPPER("protocol mapper"),
    FLOW("authentication flow"),
    EXECUTION("authentication execution"),
    REQUIRED_ACTION("required action"),
    USER_PROFILE("user profile"),
    DEFAULT_ROLE_COMPOSITE("default role composite"),
    USER("user"),
    PASSWORD("password"),
    PERMANENT_ADMIN("permanent admin");

    private final String label;

    Target(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }
}
