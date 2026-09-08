package de.cronn.keycloak.provisioning.connection;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Logs every Keycloak response, at TRACE including its body. */
class KeycloakResponseLogger implements ClientResponseFilter {

  private static final Logger log = LoggerFactory.getLogger(KeycloakResponseLogger.class);

  @Override
  public void filter(ClientRequestContext request, ClientResponseContext response)
      throws IOException {
    if (log.isTraceEnabled()) {
      log.trace(
          "{} {} -> {} {}",
          request.getMethod(),
          request.getUri(),
          response.getStatus(),
          readBody(response));
    } else if (log.isDebugEnabled()) {
      log.debug("{} {} -> {}", request.getMethod(), request.getUri(), response.getStatus());
    }
  }

  /** Buffers the entity so the caller can still read it afterwards. */
  private String readBody(ClientResponseContext response) throws IOException {
    if (!response.hasEntity()) {
      return "";
    }
    byte[] entity = response.getEntityStream().readAllBytes();
    response.setEntityStream(new ByteArrayInputStream(entity));
    return KeycloakLoggedBody.of(new String(entity, StandardCharsets.UTF_8));
  }
}
