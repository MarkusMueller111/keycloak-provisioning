package de.cronn.keycloak.provisioning.connection;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Logs every Keycloak request, at TRACE including the entity it sends. */
class KeycloakRequestLogger implements ClientRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(KeycloakRequestLogger.class);

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Override
  public void filter(ClientRequestContext request) {
    if (log.isTraceEnabled()) {
      log.trace("{} {} {}", request.getMethod(), request.getUri(), entity(request));
    } else if (log.isDebugEnabled()) {
      log.debug("{} {}", request.getMethod(), request.getUri());
    }
  }

  /** Still the representation object here, not the marshalled body. */
  private static String entity(ClientRequestContext request) {
    if (!request.hasEntity()) {
      return "";
    }
    try {
      return KeycloakLoggedBody.of(JSON.writeValueAsString(request.getEntity()));
    } catch (JacksonException e) {
      return "[unserializable " + request.getEntityClass().getSimpleName() + "]";
    }
  }
}
