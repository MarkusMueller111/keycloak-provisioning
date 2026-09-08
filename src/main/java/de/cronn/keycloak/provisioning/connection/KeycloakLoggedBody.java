package de.cronn.keycloak.provisioning.connection;

import java.util.regex.Pattern;

/**
 * Masks and truncates a body for logging. Bodies carry the secrets this library provisions and the
 * ones Keycloak returns, so masking is a second line of defence behind logging them at TRACE only.
 */
final class KeycloakLoggedBody {

  // An untouched realm already answers ~14 KB for client-scopes, and a client is roughly 1 KB.
  private static final int HEAD_LENGTH = 64 * 1024;
  private static final int TAIL_LENGTH = 4 * 1024;

  private static final Pattern SECRETS =
      Pattern.compile(
          "\"(access_token|refresh_token|id_token|secret|value|credentials|privateKey|"
              + "client_secret|password)\"\\s*:\\s*\"[^\"]*\"",
          Pattern.CASE_INSENSITIVE);

  private KeycloakLoggedBody() {}

  static String of(String body) {
    // Mask first, so a secret cannot survive in the retained tail.
    return truncate(mask(body));
  }

  private static String mask(String body) {
    return SECRETS.matcher(body).replaceAll(match -> "\"" + match.group(1) + "\":\"[MASKED]\"");
  }

  /** Keeps the tail as well: a list response carries what was just written at either end. */
  private static String truncate(String body) {
    if (body.length() <= HEAD_LENGTH + TAIL_LENGTH) {
      return body;
    }
    int omitted = body.length() - HEAD_LENGTH - TAIL_LENGTH;
    return body.substring(0, HEAD_LENGTH)
        + "... ["
        + omitted
        + " of "
        + body.length()
        + " characters omitted] ..."
        + body.substring(body.length() - TAIL_LENGTH);
  }
}
