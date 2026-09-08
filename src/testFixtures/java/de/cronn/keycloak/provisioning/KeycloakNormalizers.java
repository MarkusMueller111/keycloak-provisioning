package de.cronn.keycloak.provisioning;

import de.cronn.assertions.validationfile.normalization.UuidNormalizer;
import de.cronn.assertions.validationfile.normalization.ValidationNormalizer;
import de.cronn.assertions.validationfile.replacements.Replacer;
import java.util.Set;

/**
 * Normalizers for Keycloak snapshots, usable independently of {@link KeycloakTestTraits} so that
 * projects can combine them with their own.
 */
public final class KeycloakNormalizers {

  private static final Set<String> ID_NAMES =
      Set.of(
          "id",
          "containerId",
          "refresh_token_id",
          "token_id",
          "userId",
          "sessionId",
          "realmId",
          "flowId",
          "authenticationConfig",
          "ipAddress",
          "internalId");

  private static final Set<String> TIMESTAMP_NAMES =
      Set.of("timestamp", "createdTimestamp", "time", "createdDate");

  private static final ValidationNormalizer IDS = jsonKeyReplacements(ID_NAMES);

  private static final ValidationNormalizer TIMESTAMPS = jsonKeyReplacements(TIMESTAMP_NAMES);

  private KeycloakNormalizers() {}

  /** Everything below, combined, for the given Keycloak base URL. */
  public static ValidationNormalizer forKeycloak(String keycloakUrl) {
    return new UuidNormalizer()
        .and(ids())
        .and(timestamps())
        .and(keycloakUrl(keycloakUrl))
        .and(secretsAndCertificates());
  }

  public static ValidationNormalizer ids() {
    return IDS;
  }

  public static ValidationNormalizer timestamps() {
    return TIMESTAMPS;
  }

  public static Replacer keycloakUrl(String keycloakUrl) {
    return new Replacer(keycloakUrl, "[KEYCLOAK_URL]");
  }

  public static ValidationNormalizer secretsAndCertificates() {
    return Replacer.forJson()
        .withKey("client.secret.creation.time")
        .build()
        .and(Replacer.forJson().withKey("saml.artifact.binding.identifier").build())
        .and(Replacer.forJson().withKey("saml.signing.certificate").build())
        .and(Replacer.forJson().withKey("saml.encryption.certificate").build())
        .and(Replacer.forJson().withKey("secret").withStringValue(".*").build());
  }

  private static ValidationNormalizer jsonKeyReplacements(Set<String> keys) {
    return keys.stream()
        .map(key -> Replacer.forJson().withKey(key).withReplacement("[" + key + "]").build())
        .reduce(ValidationNormalizer.doNothing(), ValidationNormalizer::and);
  }
}
