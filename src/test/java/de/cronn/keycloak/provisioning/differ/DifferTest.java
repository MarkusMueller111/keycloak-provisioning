package de.cronn.keycloak.provisioning.differ;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DifferTest {

  /** A client attribute carries its secret under a dotted key of its own. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "secret",
        "password",
        "secretData",
        "credentialData",
        "privateKey",
        "my.api.secret",
        "my_api_secret",
        "my-api-secret",
        "oauth2.device.secret",
        "client.secret.creation.time",
        "saml.signing.private.key"
      })
  void masksAValueWhoseKeyNamesASecret(String key) {
    assertThat(diffOf(key)).doesNotContain("OLD", "NEW").contains("[masked]");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "passwordPolicy",
        "passwordless",
        "secretRotated",
        "keyword",
        "value",
        "validations",
        "redirectUris",
        "displayName"
      })
  void keepsAValueWhoseKeyMerelyResemblesOne(String key) {
    assertThat(diffOf(key)).contains("OLD", "NEW").doesNotContain("[masked]");
  }

  @Test
  void masksBothSidesOfAChangedSecret() {
    assertThat(diffOf("secret").lines().filter(line -> line.contains("[masked]")).count())
        .isEqualTo(2);
  }

  private static String diffOf(String key) {
    String before = "{\n  \"%s\" : \"OLD\"\n}".formatted(key);
    return Differ.calculateMultilineDiff(before, before.replace("OLD", "NEW"));
  }
}
