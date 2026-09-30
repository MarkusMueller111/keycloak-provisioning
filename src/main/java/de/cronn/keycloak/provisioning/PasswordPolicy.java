package de.cronn.keycloak.provisioning;

import java.util.List;

/** Keycloak's password policy string, which it stores verbatim on the realm. */
public final class PasswordPolicy {

  /**
   * Keycloak ships no policy at all, so a realm without one accepts a single character. Applied to
   * every realm this library provisions; pass a policy of your own to replace it, or an empty
   * string to opt out.
   */
  public static final String DEFAULT =
      String.join(
          " and ",
          List.of(
              "length(12)",
              "notContainsUsername",
              "upperCase(1)",
              "lowerCase(1)",
              "digits(1)",
              "specialChars(1)",
              "maxAuthAge(0)"));

  private PasswordPolicy() {}
}
