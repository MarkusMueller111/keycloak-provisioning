package de.cronn.keycloak.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.cronn.assertions.validationfile.normalization.UuidNormalizer;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.LoggerFactory;

class RequestResponseLoggingTest extends SharedKeycloakTest {

  private static final String LOGGER = "de.cronn.keycloak.provisioning.connection";

  private ListAppender<ILoggingEvent> appender;
  private ch.qos.logback.classic.Logger logger;
  private Level originalLevel;

  @BeforeEach
  void captureLog() {
    logger = ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger(LOGGER);
    originalLevel = logger.getLevel();
    appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void restoreLog() {
    logger.detachAppender(appender);
    logger.setLevel(originalLevel);
  }

  /**
   * A single role rather than a client: Keycloak returns client scopes in an unstable order, which
   * a text snapshot cannot normalize the way the representation-based ones do.
   */
  @Test
  void logsRequestsAndResponsesWithBodiesAtTrace() {
    logger.setLevel(Level.TRACE);

    withRealmManager(
        realmManager -> {
          RoleRepresentation role = new RoleRepresentation();
          role.setName("logged-role");
          realmManager.getRealmResource().roles().create(role);
          realmManager.getRealmResource().roles().get("logged-role").toRepresentation();
        });

    assertLog();
  }

  @Test
  void logsOnlyMethodUriAndStatusAtDebug() {
    logger.setLevel(Level.DEBUG);

    withRealmManager(
        realmManager -> realmManager.createOrUpdateClients(List.of(client("system-a"))));

    assertLog();
  }

  /** The filters buffer the response, so reading it afterwards has to still work. */
  @Test
  void leavesTheResponseReadableForTheCaller() {
    logger.setLevel(Level.TRACE);

    withRealmManager(
        realmManager -> {
          realmManager.createOrUpdateClients(List.of(client("system-readable")));

          List<String> clientIds =
              realmManager.getRealmResource().clients().findAll().stream()
                  .map(ClientRepresentation::getClientId)
                  .toList();
          assertThat(clientIds).contains("system-readable");
        });
  }

  @Test
  void neverLogsASecretVerbatim() {
    logger.setLevel(Level.TRACE);

    withRealmManager(
        realmManager -> {
          ClientRepresentation client = client("system-with-secret");
          client.setSecret("t0p-s3cr3t");
          realmManager.createOrUpdateClients(List.of(client));
        });

    assertThat(messages()).noneMatch(message -> message.contains("t0p-s3cr3t"));
    assertThat(messages()).noneMatch(message -> message.contains("\"access_token\":\"ey"));
  }

  @Test
  void reportsTheStatusAndPointsAtTheLogWhenACallFails() {
    logger.setLevel(Level.INFO);

    assertThatThrownBy(
            () ->
                withRealmManager(
                    realmManager -> {
                      UserRepresentation user = new UserRepresentation();
                      user.setUsername("duplicate");
                      realmManager.createUser(user);
                      realmManager.createUser(user);
                    }))
        .isInstanceOf(KeycloakException.class)
        .hasMessageContaining("enable TRACE logging")
        .hasMessageContaining("User already exists");
  }

  /**
   * A log line is text that happens to contain JSON, so the JSON-aware normalizers would rewrite it
   * into something unreadable. Only the ones that work on plain text apply.
   */
  private void assertLog() {
    assertWithFile(
        String.join("\n", provisioningMessages()),
        new UuidNormalizer().and(KeycloakNormalizers.keycloakUrl(keycloakUrl())));
  }

  /**
   * Token and bootstrap calls are left out: they run once per container, so whether a test sees
   * them depends on which one got there first.
   */
  private List<String> provisioningMessages() {
    return messages().stream()
        .filter(message -> !message.contains("/protocol/openid-connect/token"))
        .filter(message -> !message.contains("system-provisioning"))
        .filter(message -> !message.contains("/realms/master"))
        .toList();
  }

  private List<String> messages() {
    return appender.list.stream()
        .filter(event -> event.getLoggerName().endsWith("Logger"))
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  private static ClientRepresentation client(String clientId) {
    ClientRepresentation client = new ClientRepresentation();
    client.setClientId(clientId);
    client.setEnabled(true);
    return client;
  }
}
