package de.cronn.keycloak.provisioning.connection;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class KeycloakLoggedBodyTest {

  /** A token request serializes its form values as arrays, one element per value. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"password\":[\"s3cret\"]}",
        "{\"password\":[\"s3cret\",\"s3cret\"]}",
        "{\"password\": [ \"s3cret\" ]}",
        "{\"password\":[\"a]b\",\"s3cret\"]}",
        "{\"client_secret\":[\"s3cret\"]}",
        "{\"password\":\"s3cret\"}",
        "{\"password\":\"a\\\"s3cret\"}",
        "{\"credentials\":[{\"value\":\"s3cret\"}]}"
      })
  void masksASecretWhateverShapeItsValueTakes(String body) {
    assertThat(KeycloakLoggedBody.of(body)).doesNotContain("s3cret").contains("[MASKED]");
  }

  @Test
  void keepsWhatFollowsTheSecret() {
    assertThat(KeycloakLoggedBody.of("{\"password\":[\"s3cret\"],\"scope\":[\"openid\"]}"))
        .doesNotContain("s3cret")
        .contains("openid");
  }

  /** The key is what marks a secret, so the same word as a value stays readable. */
  @Test
  void keepsAValueThatMerelyReadsLikeASecretKey() {
    assertThat(KeycloakLoggedBody.of("{\"grant_type\":[\"password\"]}")).contains("password");
  }
}
