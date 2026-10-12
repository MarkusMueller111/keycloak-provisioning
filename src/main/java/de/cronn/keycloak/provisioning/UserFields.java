package de.cronn.keycloak.provisioning;

import java.util.Locale;
import org.apache.commons.lang3.Validate;

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

  private static String requireLowerCase(String field, String value) {
    Validate.isTrue(
        value.equals(value.toLowerCase(Locale.ROOT)),
        "%s must be lower-case, since Keycloak stores it that way, but was '%s'",
        field,
        value);
    return value;
  }
}
