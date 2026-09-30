package de.cronn.keycloak.provisioning;

import java.util.List;

public final class PasswordPolicy {

  /** Keycloak ships no policy at all, so a realm without one accepts a single character. */
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
