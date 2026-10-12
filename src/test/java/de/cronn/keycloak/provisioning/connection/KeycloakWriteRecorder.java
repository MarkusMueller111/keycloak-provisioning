package de.cronn.keycloak.provisioning.connection;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;

/**
 * Records every write request sent to Keycloak while it is open, whether or not the code that sent
 * it reported a change.
 *
 * <p>Reads the request logger rather than registering a filter of its own: the HTTP client is built
 * inside the connection managers, out of a test's reach.
 */
public final class KeycloakWriteRecorder implements AutoCloseable {

  private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE");
  private static final Pattern UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  private final Logger requestLogger =
      (Logger) LoggerFactory.getLogger(KeycloakRequestLogger.class);
  private final Level originalLevel = requestLogger.getLevel();
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  public KeycloakWriteRecorder() {
    if (!requestLogger.isDebugEnabled()) {
      requestLogger.setLevel(Level.DEBUG);
    }
    appender.start();
    requestLogger.addAppender(appender);
  }

  /** As {@code METHOD /path} with ids as {@code <id>}; acquiring a token is left out. */
  public List<String> writes() {
    return appender.list.stream()
        .map(ILoggingEvent::getArgumentArray)
        .filter(arguments -> WRITE_METHODS.contains(String.valueOf(arguments[0])))
        .map(arguments -> arguments[0] + " " + URI.create(String.valueOf(arguments[1])).getPath())
        .filter(write -> !write.endsWith("/protocol/openid-connect/token"))
        .map(write -> UUID.matcher(write).replaceAll("<id>"))
        .toList();
  }

  @Override
  public void close() {
    requestLogger.detachAppender(appender);
    requestLogger.setLevel(originalLevel);
  }
}
