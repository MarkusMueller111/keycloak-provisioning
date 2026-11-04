package de.cronn.keycloak.provisioning;

import java.util.Locale;
import org.apache.commons.lang3.Validate;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

/** Validation shared by everything that creates users from configuration. */
public final class UserFields {

  private UserFields() {}

  /**
   * Keycloak stores usernames lower-cased, so a mixed-case one is never the name the realm actually
   * holds. Provisioning then compares the configured spelling against the stored one and takes the
   * user for unmanaged, deleting the account it just created. Rejected rather than lower-cased
   * silently, so the configuration says what the realm will contain.
   */
  public static String requireLowerCaseUsername(String username) {
    Validate.notBlank(username, "username must not be blank");
    return requireLowerCase("username", username);
  }

  /** Undeclared is fine: an email is optional, unlike a username. */
  public static String requireLowerCaseEmail(String email) {
    return email == null ? null : requireLowerCase("email", email);
  }

  /**
   * Keycloak ignores all three on creation, so a declaration would look applied while nothing was
   * granted.
   */
  static void requireNoMembershipsDeclared(UserRepresentation user) {
    requireNotDeclared(user.getUsername(), "realmRoles", user.getRealmRoles());
    requireNotDeclared(user.getUsername(), "clientRoles", user.getClientRoles());
    requireNotDeclared(user.getUsername(), "groups", user.getGroups());
  }

  private static void requireNotDeclared(String username, String what, Object value) {
    if (value != null) {
      throw new KeycloakException(
          "User '"
              + username
              + "' declares "
              + what
              + ", which are not applied from a user. Grant memberships through"
              + " GroupAdministration instead.");
    }
  }

  /** Keycloak's reset-password endpoint ignores the type and stores any value as the password. */
  static void requireOnlyPasswordsDeclared(UserRepresentation user) {
    if (user.getCredentials() == null) {
      return;
    }
    for (CredentialRepresentation credential : user.getCredentials()) {
      if (!CredentialRepresentation.PASSWORD.equals(credential.getType())) {
        throw new KeycloakException(
            "User '"
                + user.getUsername()
                + "' declares a credential of type '"
                + credential.getType()
                + "'. Only passwords can be provisioned.");
      }
    }
  }

  private static String requireLowerCase(String field, String value) {
    Validate.isTrue(
        value.equals(value.toLowerCase(Locale.ROOT)),
        "%s must be lower-case, since Keycloak stores it that way, but was '%s'",
        field,
        value);
    return value;
  }
}
