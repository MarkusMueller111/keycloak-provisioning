package de.cronn.keycloak.provisioning.connection;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

class BootstrapRecoveryTest {

  private static final String MISSING_PROOF =
      "{\"error\":\"invalid_request\",\"error_description\":\"DPoP proof is missing\"}";
  private static final String OTHER_BAD_REQUEST =
      "{\"error\":\"invalid_request\",\"error_description\":\"Missing parameter: grant_type\"}";

  @Test
  void recoversFromAMissingDPoPProofOnlyWhenTheConnectionSendsNone() {
    assertThat(
            DefaultKeycloakConnectionManager.isRecoverableByBootstrapping(
                badRequest(MISSING_PROOF), false))
        .isTrue();
    assertThat(
            DefaultKeycloakConnectionManager.isRecoverableByBootstrapping(
                badRequest(MISSING_PROOF), true))
        .isFalse();
  }

  @Test
  void doesNotRecoverFromOtherBadRequests() {
    assertThat(
            DefaultKeycloakConnectionManager.isRecoverableByBootstrapping(
                badRequest(OTHER_BAD_REQUEST), false))
        .isFalse();
  }

  private static RuntimeException badRequest(String body) {
    Response response = Response.status(400).type(MediaType.APPLICATION_JSON).entity(body).build();
    return new RuntimeException(new BadRequestException(response));
  }
}
