package de.cronn.keycloak.provisioning;

import java.util.Locale;
import org.apache.commons.lang3.Validate;

/** Validation shared by everything that creates users from configuration. */
public final class Usernames {

  private Usernames() {}

  /**
   * Keycloak stores usernames lower-cased, so a mixed-case one is never the name the realm actually
   * holds. Reconciliation then compares the configured spelling against the stored one and takes
   * the user for unmanaged, deleting the account it just created. Rejected rather than lower-cased
   * silently, so the configuration says what the realm will contain.
   */
  public static String requireLowerCase(String username) {
    Validate.notBlank(username, "username must not be blank");
    Validate.isTrue(
        username.equals(username.toLowerCase(Locale.ROOT)),
        "username must be lower-case, since Keycloak stores it that way, but was '%s'",
        username);
    return username;
  }
}
